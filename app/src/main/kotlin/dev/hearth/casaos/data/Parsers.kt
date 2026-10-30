package dev.hearth.casaos.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * CasaOS payloads vary between releases (and several are Go structs marshalled as-is), so these parsers
 * read only the fields we need and tolerate anything missing instead of failing the whole screen.
 */

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
private fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.lng(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

fun parseUtilization(data: JsonElement?, nowMs: Long): SystemStats {
    val root = data.obj() ?: throw CasaException.Parse("Unexpected utilization payload")
    val cpu = root["cpu"].obj()
    val mem = root["mem"].obj()
    val net = root["net"].arr().orEmpty()

    val temp = cpu?.get("temperature").lng()?.toInt()?.takeIf { it > 0 }
    val memTotal = mem?.get("total").lng() ?: 0L
    val memUsed = mem?.get("used").lng() ?: 0L

    // `sys_disk` is injected by the notify service; absent on some builds.
    val disk = root["sys_disk"].obj()?.let { d ->
        val size = d["size"].lng() ?: return@let null
        val used = d["used"].lng() ?: (size - (d["avail"].lng() ?: size))
        DiskStats(totalBytes = size, usedBytes = used)
    }

    return SystemStats(
        cpuPercent = cpu?.get("percent").dbl() ?: 0.0,
        cpuCores = cpu?.get("num").lng()?.toInt() ?: 0,
        cpuTempC = temp,
        cpuModel = cpu?.get("model").str().orEmpty(),
        memTotal = memTotal,
        memUsed = memUsed,
        memPercent = mem?.get("usedPercent").dbl() ?: if (memTotal > 0) memUsed * 100.0 / memTotal else 0.0,
        disk = disk,
        netRxBytes = net.sumOf { it.obj()?.get("bytesRecv").lng() ?: 0L },
        netTxBytes = net.sumOf { it.obj()?.get("bytesSent").lng() ?: 0L },
        sampledAtMs = nowMs,
    )
}

/**
 * Picks the best localized string from a `{ "en_us": "…" }` map, falling back to any entry. A name the user
 * gave the app in its settings is stored as `custom` and wins.
 */
private fun JsonElement?.localized(): String? {
    val o = this.obj() ?: return this.str()
    return listOf("custom", "en_us", "en_US").firstNotNullOfOrNull { k -> o[k].str()?.takeIf { it.isNotBlank() } }
        ?: o.values.firstOrNull { it.str()?.isNotBlank() == true }.str()
}

fun parseApps(data: JsonElement?): List<InstalledApp> {
    val apps = data.obj() ?: return emptyList()
    return apps.mapNotNull { (id, value) ->
        val app = value.obj() ?: return@mapNotNull null
        val store = app["store_info"].obj()
        val status = app["status"].str().orEmpty()

        // Store apps declare their web UI port; custom compose apps only have the raw service ports.
        val port = store?.get("port_map").str()?.takeIf { it.isNotBlank() }
            ?: firstPublishedPort(app["compose"].obj())

        InstalledApp(
            id = id,
            name = store?.get("title").localized() ?: id,
            tagline = store?.get("tagline").localized()?.takeIf { it.isNotBlank() },
            category = store?.get("category").str()?.takeIf { it.isNotBlank() },
            iconUrl = store?.get("icon").str()?.takeIf { it.startsWith("http") },
            state = appState(status),
            rawStatus = status,
            updateAvailable = (app["update_available"] as? JsonPrimitive)?.booleanOrNull ?: false,
            webPort = port,
            webPath = store?.get("index").str()?.takeIf { it.isNotBlank() } ?: "/",
            scheme = store?.get("scheme").str()?.takeIf { it.isNotBlank() } ?: "http",
            image = mainImage(app["compose"].obj(), store?.get("main").str()),
        )
    }.sortedBy { it.name.lowercase() }
}

/** The image of the app's main container: the one the store names, else the first. */
private fun mainImage(compose: JsonObject?, main: String?): String? {
    val services = compose?.get("services").obj() ?: return null
    val name = main?.takeIf { it.isNotBlank() } ?: compose?.get("x-casaos").obj()?.get("main").str()
    val service = (name?.let { services[it] } ?: services.values.firstOrNull()).obj()
    return service?.get("image").str()?.takeIf { it.isNotBlank() }
}

private fun firstPublishedPort(compose: JsonObject?): String? {
    val services = compose?.get("services").obj() ?: return null
    for (service in services.values) {
        for (port in service.obj()?.get("ports").arr().orEmpty()) {
            port.obj()?.get("published").str()?.takeIf { it.isNotBlank() && it != "0" }?.let { return it }
        }
    }
    return null
}

fun appState(raw: String): AppState {
    val s = raw.lowercase()
    return when {
        s.isEmpty() -> AppState.Unknown
        "restart" in s -> AppState.Restarting
        "running" in s || s == "up" || s.startsWith("up ") -> AppState.Running
        "stop" in s || "exit" in s || "created" in s || "paused" in s || "down" in s -> AppState.Stopped
        else -> AppState.Unknown
    }
}

/**
 * Explains why CasaOS refused an app's compose file, from the `data` of its 400 reply:
 * `{"ports_in_use": {"TCP": ["8080"], "UDP": []}}` or `{"ports_out_of_range": ["70000"]}`. Null for anything else.
 */
fun validationMessage(data: JsonElement?): String? {
    val o = data.obj() ?: return null
    o["ports_in_use"].obj()?.let { inUse ->
        val ports = inUse["TCP"].arr().orEmpty().mapNotNull { it.str() } +
            inUse["UDP"].arr().orEmpty().mapNotNull { it.str()?.let { p -> "$p/udp" } }
        if (ports.isNotEmpty()) {
            return if (ports.size == 1) "Port ${ports[0]} is already used by something else on the server."
            else "Ports ${ports.joinToString(", ")} are already used by something else on the server."
        }
    }
    val outOfRange = o["ports_out_of_range"].arr().orEmpty().mapNotNull { it.str() }
    if (outOfRange.isNotEmpty()) return "Port ${outOfRange.joinToString(", ")} is out of range (1 to 65535)."
    return null
}

fun parseFolder(path: String, data: JsonElement?): FolderListing {
    val content = data.obj()?.get("content").arr().orEmpty()
    val entries = content.mapNotNull { el ->
        val o = el.obj() ?: return@mapNotNull null
        val name = o["name"].str() ?: return@mapNotNull null
        FileEntry(
            name = name,
            path = o["path"].str() ?: (path.trimEnd('/') + "/" + name),
            isDir = (o["is_dir"] as? JsonPrimitive)?.booleanOrNull ?: false,
            size = o["size"].lng() ?: 0L,
            modified = (o["modified"].str() ?: o["date"].str())?.takeIf { it.isNotBlank() },
        )
    }
    // Folders first, then case-insensitive name order: how every file manager is expected to behave.
    return FolderListing(path, entries.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() }))
}

/**
 * Turns whatever the user typed into a base URL like `http://192.168.1.10:8080`, or null if unusable.
 * Defaults to plain http because CasaOS ships without TLS and is normally reached over the LAN.
 */
fun normalizeBaseUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    // Decide on the scheme before touching trailing slashes, or "http://" collapses to "http:" and parses as a host.
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    return buildString {
        append(url.scheme).append("://")
        append(if (':' in url.host) "[${url.host}]" else url.host)
        val defaultPort = if (url.scheme == "https") 443 else 80
        if (url.port != defaultPort) append(':').append(url.port)
    }
}

/** URL for opening the app's web UI on the same host as the CasaOS server, or null if it has no web port. */
fun InstalledApp.webUrl(serverBaseUrl: String): String? {
    val port = webPort ?: return null
    val host = serverBaseUrl.toHttpUrlOrNull()?.host ?: return null
    val path = if (webPath.startsWith("/")) webPath else "/$webPath"
    val hostPart = if (':' in host) "[$host]" else host
    return "$scheme://$hostPart:$port$path"
}

/**
 * The App Store listing: `{list: {id: info}, installed: [id…]}`. Each info carries the title, icon and so on
 * itself; older stores keep them on the main container's entry under `apps`, so that's the fallback.
 */
fun parseStoreApps(data: JsonElement?): List<StoreApp> {
    val root = data.obj() ?: return emptyList()
    val installed = root["installed"].arr().orEmpty().mapNotNull { it.str()?.lowercase() }.toSet()
    return root["list"].obj().orEmpty().mapNotNull { (id, value) ->
        val info = value.obj() ?: return@mapNotNull null
        val main = info["apps"].obj()?.let { apps -> (info["main"].str()?.let { apps[it] } ?: apps.values.firstOrNull()).obj() }
        fun field(name: String): JsonElement? = info[name]?.takeIf { it !is JsonNull } ?: main?.get(name)
        val title = field("title").localized()?.takeIf { it.isNotBlank() } ?: id
        StoreApp(
            id = id,
            title = title,
            tagline = field("tagline").localized()?.takeIf { it.isNotBlank() },
            description = field("description").localized()?.trim()?.takeIf { it.isNotBlank() },
            category = field("category").str()?.takeIf { it.isNotBlank() },
            developer = field("developer").str()?.takeIf { it.isNotBlank() } ?: field("author").str()?.takeIf { it.isNotBlank() },
            iconUrl = field("icon").str()?.takeIf { it.startsWith("http") },
            screenshots = field("screenshot_link").arr().orEmpty().mapNotNull { it.str()?.takeIf { s -> s.startsWith("http") } },
            installed = id.lowercase() in installed,
        )
    }.sortedBy { it.title.lowercase() }
}
