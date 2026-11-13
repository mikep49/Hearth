package dev.hearth.casaos.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** One machine on the tailnet, as the server's Tailscale reports it. */
data class TailscaleDevice(
    val name: String,
    /** Its MagicDNS name, such as `casaos.tail1234.ts.net`, on tailnets that have MagicDNS. */
    val dnsName: String?,
    val os: String?,
    val addresses: List<String>,
    val online: Boolean,
    /** Whether anything went between it and the server in the last couple of minutes. */
    val active: Boolean,
    /** Where the server reaches it while the two talk directly, without a relay. */
    val directAddress: String?,
    /** The code of the Tailscale relay region nearest to it, such as `fra`. */
    val relay: String?,
    /** What the server has received from it and sent to it. */
    val rxBytes: Long,
    val txBytes: Long,
    /** The server sends its internet traffic through this device. */
    val exitNode: Boolean,
    /** It offers to be an exit node for the tailnet. */
    val offersExitNode: Boolean,
    /** The subnets it shares with the tailnet, such as `192.168.1.0/24`. */
    val subnets: List<String>,
    /** When its sign-in runs out, unless key expiry is turned off for it. */
    val keyExpiry: Instant?,
) {
    /** Its IPv4 address, which is the one people type. */
    val address: String? get() = addresses.firstOrNull { ':' !in it } ?: addresses.firstOrNull()

    val path: TailscalePath
        get() = when {
            directAddress != null -> TailscalePath.Direct
            active -> TailscalePath.Relayed
            else -> TailscalePath.Idle
        }
}

/** How traffic gets between the server and another device: straight there, through a relay, or none lately. */
enum class TailscalePath { Direct, Relayed, Idle }

/** Tailscale's own states, as `BackendState` in its status. */
enum class TailscaleBackend(val label: String) {
    Running("Connected"),
    Stopped("Stopped"),
    NeedsLogin("Signed out"),
    NeedsMachineAuth("Waiting for approval"),
    Starting("Starting"),
    Unknown("Unknown"),
}

/** What `tailscale status --json` says on the server. */
data class TailscaleStatus(
    val backend: TailscaleBackend,
    val version: String?,
    val tailnet: String?,
    /** The login the server joined the tailnet with. */
    val account: String?,
    val self: TailscaleDevice?,
    val peers: List<TailscaleDevice>,
    /** Problems Tailscale reports about itself, in its own words. */
    val health: List<String>,
    /** The Docker container Tailscale runs in, or null when it's installed on the server itself. */
    val container: String? = null,
) {
    /** The device the server uses as its exit node, if it uses one. */
    val exitNode: TailscaleDevice? get() = peers.firstOrNull { it.exitNode }

    /** The device with one of [addresses], which is how the phone finds itself in the server's list. */
    fun deviceWith(addresses: List<String>): TailscaleDevice? =
        peers.firstOrNull { peer -> peer.addresses.any { it in addresses } }
}

/**
 * Reads the server's Tailscale over SSH, using the password or key the user saved on the Terminal tab.
 * Tailscale can be installed on the server itself or run as an app in Docker, so this looks in both.
 * Keeps one login open between readings; [close] it when done.
 */
class TailscaleSource(profile: () -> SshProfile, hostKeys: HostKeyStore) {
    private val ssh = SavedSsh(profile, hostKeys, "see the server's Tailscale details")

    /** The container Tailscale was found in last time, so the next reading asks it directly. */
    private var container: String? = null

    /** The server's status, or null if Tailscale isn't installed on it. Throws [SshUnavailable]. */
    suspend fun read(): TailscaleStatus? = ssh.read reading@{
        container?.let { known ->
            try {
                return@reading parse(docker("exec $known $STATUS")).copy(container = known)
            } catch (e: SshUnavailable) {
                container = null // stopped, renamed or removed: look again
            }
        }
        val onServer = run(STATUS)
        if (onServer.exitCode == 0) return@reading parse(onServer.stdout)
        if (onServer.exitCode != 127) throw SshUnavailable(tailscaleError(onServer.stderr))
        // No `tailscale` command on the server itself, so look for it running as an app in Docker.
        if (run("command -v docker").exitCode != 0) return@reading null
        val found = tailscaleContainer(docker("ps --format '{{.Names}}|{{.Image}}'")) ?: return@reading null
        parse(docker("exec $found $STATUS")).copy(container = found).also { container = found }
    }

    private fun parse(json: String): TailscaleStatus =
        parseTailscaleStatus(json) ?: throw SshUnavailable("Couldn't read Tailscale's answer.")

    suspend fun close() = ssh.close()

    private companion object {
        const val STATUS = "tailscale status --json"
    }
}

/** Says why `tailscale status` failed, from what it printed. */
internal fun tailscaleError(stderr: String): String {
    val line = stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    return when {
        line == null -> "Tailscale didn't answer."
        "tailscaled" in line -> "Tailscale is installed on this server, but it isn't running."
        else -> "Tailscale: $line"
    }
}

/**
 * The running container whose image is Tailscale's, from `docker ps` printed as `name|image`. Only names
 * made of the characters Docker allows are taken, since the name goes into a command.
 */
internal fun tailscaleContainer(psOutput: String): String? = psOutput.lineSequence()
    .map { it.trim().split('|') }
    .firstOrNull { it.size == 2 && ContainerName.matches(it[0]) && "tailscale" in it[1].substringBefore(':').lowercase() }
    ?.get(0)

private val ContainerName = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
private fun JsonElement?.flag(): Boolean = (this as? JsonPrimitive)?.booleanOrNull == true
private fun JsonElement?.strings(): List<String> = (this as? JsonArray).orEmpty().mapNotNull { it.str() }

/** Reads `tailscale status --json`. Null if [json] isn't a status at all. Fields come and go between versions, so each is optional. */
fun parseTailscaleStatus(json: String): TailscaleStatus? {
    val root = runCatching { lenient.parseToJsonElement(json) }.getOrNull().obj() ?: return null
    val state = root["BackendState"].str() ?: return null
    val self = root["Self"].obj()
    val account = self?.get("UserID").str()?.let { id -> root["User"].obj()?.get(id).obj()?.get("LoginName").str() }
    return TailscaleStatus(
        backend = TailscaleBackend.entries.firstOrNull { it.name == state } ?: TailscaleBackend.Unknown,
        // "1.80.2-t1a2b3c4d5-g6e7f8a9b0" is the release followed by the commits it was built from.
        version = root["Version"].str()?.substringBefore('-'),
        tailnet = root["CurrentTailnet"].obj()?.get("Name").str(),
        account = account,
        self = self?.let(::parseDevice),
        peers = root["Peer"].obj()?.values.orEmpty().mapNotNull { it.obj()?.let(::parseDevice) },
        health = root["Health"].strings(),
    )
}

private fun parseDevice(o: JsonObject): TailscaleDevice {
    val dnsName = o["DNSName"].str()?.trimEnd('.')
    return TailscaleDevice(
        // The name it has on the tailnet, which the admin can change; the machine's own name is the fallback.
        name = dnsName?.substringBefore('.') ?: o["HostName"].str() ?: "Unnamed device",
        dnsName = dnsName,
        os = o["OS"].str(),
        addresses = o["TailscaleIPs"].strings(),
        online = o["Online"].flag(),
        active = o["Active"].flag(),
        directAddress = o["CurAddr"].str(),
        relay = o["Relay"].str(),
        rxBytes = (o["RxBytes"] as? JsonPrimitive)?.longOrNull ?: 0L,
        txBytes = (o["TxBytes"] as? JsonPrimitive)?.longOrNull ?: 0L,
        exitNode = o["ExitNode"].flag(),
        offersExitNode = o["ExitNodeOption"].flag(),
        subnets = o["PrimaryRoutes"].strings(),
        keyExpiry = o["KeyExpiry"].str()?.let { runCatching { Instant.parse(it) }.getOrNull() },
    )
}

/** Whether [address] is one Tailscale gives its devices: 100.64.0.0/10, or IPv6 under fd7a:115c:a1e0::/48. */
fun isTailscaleAddress(address: String): Boolean {
    val parts = ipv4Parts(address)
    if (parts != null) return parts[0] == 100 && parts[1] in 64..127
    return bare(address).startsWith("fd7a:115c:a1e0:", ignoreCase = true)
}

/** Whether [address] only works on the network it's on: a home or office range, or the phone itself. */
private fun isPrivateAddress(address: String): Boolean {
    val parts = ipv4Parts(address)
    if (parts != null) {
        return parts[0] == 10 || parts[0] == 127 || parts[0] == 192 && parts[1] == 168 ||
            parts[0] == 172 && parts[1] in 16..31 || parts[0] == 169 && parts[1] == 254
    }
    val a = bare(address).lowercase()
    return ':' in a && (a == "::1" || a.startsWith("fe80:") || a.startsWith("fc") || a.startsWith("fd"))
}

/** An IPv6 address without the brackets a URL puts round it, or its `%wlan0` zone. */
private fun bare(address: String) = address.trim().removePrefix("[").removeSuffix("]").substringBefore('%')

private fun ipv4Parts(address: String): List<Int>? =
    address.trim().split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }

/** How Hearth reaches the server it's showing. */
enum class ServerRoute { Tailscale, LocalNetwork, Other }

/**
 * Works out the route from the server's [host] as it was typed at sign-in. [resolved] is the addresses a
 * name looks up to, for names that don't say by themselves.
 */
fun serverRoute(host: String, resolved: List<String> = emptyList()): ServerRoute {
    val name = host.lowercase().trimEnd('.')
    val all = resolved + name
    return when {
        name.endsWith(".ts.net") || all.any(::isTailscaleAddress) -> ServerRoute.Tailscale
        name.endsWith(".local") || all.any(::isPrivateAddress) -> ServerRoute.LocalNetwork
        else -> ServerRoute.Other
    }
}

/** Whether [host] is an address already, so there's nothing to look up. */
fun isIpAddress(host: String): Boolean = ipv4Parts(host) != null || ':' in host

/**
 * Tailscale on this phone. The Tailscale app shares nothing with other apps, so this is what Android shows
 * of the phone's VPN: [addresses] are the Tailscale addresses it has, which is none while Tailscale is off.
 */
data class PhoneTailscale(val addresses: List<String> = emptyList(), val appInstalled: Boolean = false) {
    val on: Boolean get() = addresses.isNotEmpty()

    /** Its IPv4 address, which is the one people type. */
    val address: String? get() = addresses.firstOrNull { ':' !in it } ?: addresses.firstOrNull()
}

/** Reads [PhoneTailscale] from Android, and opens the Tailscale app. */
class PhoneTailscaleReader(private val context: Context) {
    /**
     * Only VPNs are looked at: mobile networks hand out addresses from the same 100.64.0.0/10 range, and
     * those aren't Tailscale's.
     */
    @Suppress("DEPRECATION") // allNetworks: the VPN isn't always the active network, for example with split tunnelling.
    fun read(): PhoneTailscale {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val addresses = cm?.allNetworks.orEmpty().asSequence()
            .filter { cm?.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            .flatMap { cm?.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .mapNotNull { it.address.hostAddress }
            .filter(::isTailscaleAddress)
            .distinct()
            .toList()
        return PhoneTailscale(addresses, appInstalled = launchIntent() != null)
    }

    /** Opens the Tailscale app. False if it isn't installed. */
    fun openApp(): Boolean {
        val intent = launchIntent() ?: return false
        return runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    private fun launchIntent() = context.packageManager.getLaunchIntentForPackage(TAILSCALE_APP)

    private companion object {
        const val TAILSCALE_APP = "com.tailscale.ipn"
    }
}
