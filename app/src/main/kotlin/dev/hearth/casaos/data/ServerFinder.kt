package dev.hearth.casaos.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** A CasaOS server found on the local network. [address] goes in the sign-in form; [ip] tells duplicates apart. */
data class FoundServer(val address: String, val ip: String)

/** Looks for CasaOS servers on the network the phone is on. */
interface ServerFinder {
    /** The servers as they're found, or null when the phone isn't on a Wi-Fi or wired network. */
    fun find(): Flow<FoundServer>?
}

/**
 * Tries `casaos.local` and every address on the phone's subnet (at most its /24) on port 80, and keeps the
 * ones that answer like CasaOS.
 */
class LanServerFinder(private val context: Context, baseClient: OkHttpClient) : ServerFinder {
    private val probe = CasaProbe(baseClient)

    override fun find(): Flow<FoundServer>? {
        val hosts = localSubnet()?.let { (ip, prefix) -> subnetHosts(ip, prefix) } ?: return null
        return channelFlow {
            launch {
                val ip = resolveIpv4(MDNS_NAME) ?: return@launch
                if (probe.isCasaOS("http://$MDNS_NAME")) send(FoundServer(MDNS_NAME, ip))
            }
            val gate = Semaphore(PARALLEL)
            for (host in hosts) launch {
                gate.withPermit { if (probe.isCasaOS("http://$host")) send(FoundServer(host, host)) }
            }
        }.flowOn(Dispatchers.IO)
    }

    /** The phone's IPv4 address and prefix length on Wi-Fi or Ethernet, if it has a private one. */
    @Suppress("DEPRECATION") // allNetworks: the active network may be a VPN, which hides the Wi-Fi one.
    private fun localSubnet(): Pair<Inet4Address, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val networks = listOfNotNull(cm.activeNetwork) + cm.allNetworks
        return networks.asSequence()
            .filter { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@filter false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
            .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .firstOrNull { (it.address as? Inet4Address)?.isSiteLocalAddress == true }
            ?.let { it.address as Inet4Address to it.prefixLength }
    }

    /** Resolves [name] off the main thread, giving up after a few seconds (a `.local` lookup can hang). */
    private suspend fun resolveIpv4(name: String): String? = withTimeoutOrNull(4_000) {
        coroutineScope {
            async(Dispatchers.IO) {
                runCatching { InetAddress.getAllByName(name).firstOrNull { it is Inet4Address }?.hostAddress }.getOrNull()
            }.await()
        }
    }

    private companion object {
        const val MDNS_NAME = "casaos.local"
        const val PARALLEL = 48
    }
}

/** Checks whether a base URL is a CasaOS server, using only pages it serves without signing in. */
class CasaProbe(baseClient: OkHttpClient) {
    private val http = baseClient.newBuilder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()

    suspend fun isCasaOS(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        // The sign-in page asks this before anyone logs in. If it's missing, fall back to the dashboard's page title.
        looksLikeCasaStatus(fetch("$baseUrl/v1/users/status")) || looksLikeCasaPage(fetch("$baseUrl/"))
    }

    private fun fetch(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.peekBody(64 * 1024).string() else null
        }
    }.getOrNull()
}

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

/** `/v1/users/status` answers `{"success":200,"data":{"initialized":true,…}}`. */
internal fun looksLikeCasaStatus(body: String?): Boolean = runCatching {
    val root = lenient.parseToJsonElement(body ?: return false) as? JsonObject ?: return false
    (root["data"] as? JsonObject)?.containsKey("initialized") == true
}.getOrDefault(false)

/** The CasaOS dashboard's page is titled "CasaOS". */
internal fun looksLikeCasaPage(body: String?): Boolean =
    body != null && Regex("<title>\\s*CasaOS", RegexOption.IGNORE_CASE).containsMatchIn(body)

/**
 * The other addresses on [ip]'s subnet, as strings. A subnet bigger than a /24 is cut down to the /24 around
 * [ip], so a scan stays at 254 addresses or fewer.
 */
internal fun subnetHosts(ip: Inet4Address, prefix: Int): List<String> {
    val bits = prefix.coerceIn(24, 30)
    val self = ip.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    val mask = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
    val network = self and mask
    val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
    return ((network + 1) until broadcast).filter { it != self }.map { n ->
        "${n shr 24 and 0xFF}.${n shr 16 and 0xFF}.${n shr 8 and 0xFF}.${n and 0xFF}"
    }
}

/**
 * Adds [found] to [list], keeping one entry per server. When the same machine turns up by name and by
 * address, the name wins, since it survives the router handing out a new address.
 */
internal fun mergeFound(list: List<FoundServer>, found: FoundServer): List<FoundServer> {
    val i = list.indexOfFirst { it.ip == found.ip }
    val merged = when {
        i < 0 -> list + found
        list[i].address == list[i].ip && found.address != found.ip -> list.toMutableList().also { it[i] = found }
        else -> return list
    }
    // Named servers first, then by address.
    return merged.sortedWith(compareBy({ it.address == it.ip }, { ipSortKey(it.ip) }))
}

private fun ipSortKey(ip: String): Long =
    ip.split('.').fold(0L) { acc, part -> (acc shl 8) or (part.toLongOrNull() ?: 0L) }
