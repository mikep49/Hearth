package dev.hearth.casaos.data

import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.RepresentToNode
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.representer.StandardRepresenter

/*
 * Reads and writes the compose YAML CasaOS keeps for an app: the same file its dashboard's settings dialog
 * edits (`GET /v2/app_management/compose/{id}` with `Accept: application/yaml`).
 *
 * CasaOS writes it from compose-go, so lists are mostly in long syntax (`target:`/`published:` ports, `source:`/
 * `target:` volumes), but hand-written apps can use the short `"8080:80"` forms; both are read. Saving changes
 * only the settings that were edited and writes everything else back as it was.
 */

private typealias YamlMap = MutableMap<Any?, Any?>

private fun Any?.map(): Map<*, *>? = this as? Map<*, *>
private fun Any?.list(): List<*>? = this as? List<*>
private fun Any?.scalar(): String? = when (this) {
    null, is Map<*, *>, is List<*> -> null
    else -> toString()
}

@Suppress("UNCHECKED_CAST")
private fun Any?.mutableMap(): YamlMap? = this as? YamlMap

/** The child map at [key], created if missing. */
@Suppress("UNCHECKED_CAST")
private fun YamlMap.child(key: String): YamlMap =
    (this[key] as? YamlMap) ?: LinkedHashMap<Any?, Any?>().also { this[key] = it }

private fun YamlMap.putOrRemove(key: String, value: Any?) {
    if (value == null || value == "" || (value is Collection<*> && value.isEmpty())) remove(key) else this[key] = value
}

internal fun loadComposeYaml(yaml: String): YamlMap {
    val root = try {
        Load(LoadSettings.builder().build()).loadFromString(yaml)
    } catch (e: MarkedYamlEngineException) {
        // e.g. "Line 3: expected ',' or ']', but got <block mapping start>"
        val line = e.problemMark.map { it.line + 1 }.orElse(null)
        val problem = e.problem ?: e.context ?: "unexpected text"
        throw CasaException.Parse("The YAML isn't valid. ${if (line != null) "Line $line: " else ""}$problem", e)
    } catch (e: YamlEngineException) {
        throw CasaException.Parse("The YAML isn't valid: ${e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }.orEmpty()}", e)
    }
    return root.mutableMap() ?: throw CasaException.Parse("The YAML isn't a compose file.")
}

internal fun dumpComposeYaml(root: Map<Any?, Any?>): String {
    val settings = DumpSettings.builder()
        .setDefaultFlowStyle(FlowStyle.BLOCK)
        .setIndent(2)
        .setIndicatorIndent(2)
        .setIndentWithIndicator(true)
        .setSplitLines(false)
        .setDereferenceAliases(true)
        .build()
    return Dump(settings, ComposeRepresenter(settings)).dumpToString(root)
}

/**
 * Quotes strings that some YAML readers take for something else: `yes`/`on` (booleans in YAML 1.1), and
 * numbers, octal or `8080:80` (a base-60 number in YAML 1.1). An env value of `yes` must stay a string.
 */
private class ComposeRepresenter(settings: DumpSettings) : StandardRepresenter(settings) {
    init {
        val plain = representers.getValue(String::class.java)
        representers[String::class.java] = RepresentToNode { data ->
            if (Ambiguous.matches(data as String)) representScalar(Tag.STR, data, ScalarStyle.DOUBLE_QUOTED) else plain.representData(data)
        }
    }

    private companion object {
        val Ambiguous = Regex(
            "(?i)y|n|yes|no|on|off|true|false|null|~|[-+]?\\.(inf|nan)|[-+]?[0-9][0-9_:.,]*([eE][-+]?[0-9]+)?|[-+]?0[xob][0-9a-f_]+",
        )
    }
}

// region reading

/** Parses the compose YAML of an app into the settings the dashboard shows. */
fun parseAppSettings(yaml: String): AppSettings {
    val root = loadComposeYaml(yaml)
    val services = root["services"].map()?.takeIf { it.isNotEmpty() }
        ?: throw CasaException.Parse("This app's compose file has no containers.")
    val x = root["x-casaos"].map()
    val app = AppInfoSettings(
        title = localizedTitle(x?.get("title")) ?: root["name"].scalar().orEmpty(),
        icon = x?.get("icon").scalar().orEmpty(),
        scheme = x?.get("scheme").scalar()?.ifBlank { null } ?: "http",
        hostname = x?.get("hostname").scalar().orEmpty(),
        port = x?.get("port_map").scalar().orEmpty(),
        path = x?.get("index").scalar().orEmpty(),
    )
    val main = x?.get("main").scalar()
    val parsed = services.map { (name, value) -> parseService(name.toString(), value.map().orEmpty()) }
    return AppSettings(app, parsed.sortedByDescending { it.name == main })
}

/** The App Store's id for the app (`x-casaos.store_app_id`), or null for an app that didn't come from the store. */
fun parseStoreAppId(yaml: String): String? =
    loadComposeYaml(yaml)["x-casaos"].map()?.get("store_app_id").scalar()?.trim()?.ifEmpty { null }

/** A user-set name (`custom`, what the dashboard writes when renaming) beats the store's English title. */
private fun localizedTitle(raw: Any?): String? {
    val m = raw.map() ?: return raw.scalar()?.ifBlank { null }
    return listOf("custom", "en_us", "en_US").firstNotNullOfOrNull { m[it].scalar()?.ifBlank { null } }
        ?: m.values.firstNotNullOfOrNull { it.scalar()?.ifBlank { null } }
}

private fun parseService(name: String, s: Map<*, *>): ServiceSettings {
    val (image, tag) = splitImage(s["image"].scalar().orEmpty())
    return ServiceSettings(
        name = name,
        image = image,
        tag = tag,
        containerName = s["container_name"].scalar().orEmpty(),
        network = s["network_mode"].scalar()
            ?: (s["networks"].map()?.keys ?: s["networks"].list())?.joinToString(", ")?.ifBlank { null }
            ?: "",
        ports = s["ports"].list().orEmpty().mapIndexedNotNull { i, p -> parsePort(p, i) },
        volumes = s["volumes"].list().orEmpty().mapIndexedNotNull { i, v -> parseVolume(v, i) },
        environment = parseEnvironment(s["environment"]),
        devices = s["devices"].list().orEmpty().mapIndexedNotNull { i, d -> parseDevice(d, i) },
        command = when (val c = s["command"]) {
            is List<*> -> c.mapNotNull { it.scalar() }
            null -> emptyList()
            else -> listOfNotNull(c.scalar())
        },
        privileged = s["privileged"].scalar() == "true",
        memoryMb = memoryLimit(s)?.let(::bytesOf)?.takeIf { it > 0 }?.let { ((it + MB / 2) / MB).toString() }.orEmpty(),
        cpuShares = s["cpu_shares"].scalar()?.toIntOrNull()?.takeIf { it > 0 },
        restart = s["restart"].scalar().orEmpty(),
        capAdd = s["cap_add"].list().orEmpty().mapNotNull { it.scalar() },
        hints = parseHints(s["x-casaos"].map()),
    )
}

/** Splits `repo/name:tag` at the tag. A registry port (`host:5000/name`) or a digest is not a tag. */
fun splitImage(ref: String): Pair<String, String> {
    val colon = ref.lastIndexOf(':')
    if (colon < 0 || '@' in ref || colon < ref.lastIndexOf('/')) return ref to ""
    return ref.substring(0, colon) to ref.substring(colon + 1)
}

fun joinImage(image: String, tag: String) = if (tag.isEmpty()) image else "$image:$tag"

private fun parsePort(raw: Any?, origin: Int): PortMapping? = when (raw) {
    is Map<*, *> -> raw["target"].scalar()?.let { target ->
        PortMapping(
            host = raw["published"].scalar().orEmpty(),
            container = target,
            protocol = raw["protocol"].scalar()?.lowercase()?.ifBlank { null } ?: "tcp",
            origin = origin,
        )
    }
    is String, is Number -> {
        // [ip:][host:]container[/protocol]
        val text = raw.toString()
        val parts = text.substringBefore('/').split(':')
        PortMapping(
            host = if (parts.size >= 2) parts[parts.size - 2] else "",
            container = parts.last(),
            protocol = text.substringAfter('/', "tcp").lowercase(),
            origin = origin,
        )
    }
    else -> null
}

private fun parseVolume(raw: Any?, origin: Int): VolumeMapping? = when (raw) {
    is Map<*, *> -> VolumeMapping(
        host = raw["source"].scalar().orEmpty(),
        container = raw["target"].scalar().orEmpty(),
        readOnly = raw["read_only"].scalar() == "true",
        origin = origin,
    )
    is String -> {
        // [source:]target[:mode]
        val parts = raw.split(':')
        VolumeMapping(
            host = if (parts.size >= 2) parts[0] else "",
            container = if (parts.size >= 2) parts[1] else parts[0],
            readOnly = parts.getOrNull(2)?.split(',')?.contains("ro") == true,
            origin = origin,
        )
    }
    else -> null
}

private fun parseDevice(raw: Any?, origin: Int): DeviceMapping? = when (raw) {
    is Map<*, *> -> DeviceMapping(raw["source"].scalar().orEmpty(), raw["target"].scalar().orEmpty(), origin)
    is String -> {
        // host[:container[:permissions]]
        val parts = raw.split(':')
        DeviceMapping(parts[0], parts.getOrElse(1) { parts[0] }, origin)
    }
    else -> null
}

/*
 * CasaOS doubles every `$` in environment values when it writes the file (`$$` is compose's escape for a
 * literal `$`). Settings show the real value and saving escapes it again.
 */
private fun unescapeEnv(v: String) = v.replace("$$", "$")
private fun escapeEnv(v: String) = v.replace("$", "$$")

private fun parseEnvironment(raw: Any?): List<EnvVar> = when (raw) {
    is Map<*, *> -> raw.map { (k, v) -> EnvVar(k.toString(), unescapeEnv(v.scalar().orEmpty())) }
    is List<*> -> raw.mapNotNull { it.scalar() }.map { EnvVar(it.substringBefore('='), unescapeEnv(it.substringAfter('=', ""))) }
    else -> emptyList()
}

private fun memoryLimit(s: Map<*, *>): Any? =
    s["deploy"].map()?.get("resources").map()?.get("limits").map()?.get("memory") ?: s["mem_limit"]

private const val MB = 1024L * 1024

private val ByteSize = Regex("""(?i)\s*(\d+(?:\.\d+)?)\s*([kmgt]?)i?b?\s*""")

/** `268435456`, `256m`, `1.5G`… to bytes, the way Docker reads them (binary units). */
internal fun bytesOf(raw: Any?): Long? {
    val m = ByteSize.matchEntire(raw.scalar() ?: return null) ?: return null
    val number = m.groupValues[1].toDouble()
    val factor = when (m.groupValues[2].lowercase()) {
        "k" -> 1024.0
        "m" -> MB.toDouble()
        "g" -> MB * 1024.0
        "t" -> MB * 1024.0 * 1024.0
        else -> 1.0
    }
    return (number * factor).toLong()
}

private fun parseHints(x: Map<*, *>?): SettingHints {
    fun read(key: String): Map<String, String> = x?.get(key).list().orEmpty().mapNotNull { item ->
        val m = item.map() ?: return@mapNotNull null
        val container = m["container"].scalar() ?: return@mapNotNull null
        val text = localizedTitle(m["description"]) ?: return@mapNotNull null
        container to text
    }.toMap()
    return SettingHints(ports = read("ports"), volumes = read("volumes"), environment = read("envs"), devices = read("devices"))
}

// endregion

// region writing

/**
 * Writes the edits in [after] into [yaml], the compose file [before] was parsed from, and returns the new file.
 * Settings that weren't edited, and everything the form doesn't show, are left exactly as they were.
 */
fun applyAppSettings(yaml: String, before: AppSettings, after: AppSettings): String {
    val root = loadComposeYaml(yaml)
    if (after.app != before.app) writeAppInfo(root.child("x-casaos"), before.app, after.app)
    val services = root["services"].mutableMap() ?: throw CasaException.Parse("This app's compose file has no containers.")
    for (new in after.services) {
        val old = before.services.firstOrNull { it.name == new.name } ?: continue
        if (new == old) continue
        val node = services[new.name].mutableMap() ?: continue
        writeService(node, old, new)
    }
    return dumpComposeYaml(root)
}

private fun writeAppInfo(x: YamlMap, old: AppInfoSettings, new: AppInfoSettings) {
    if (new.title != old.title) {
        // The dashboard keeps the store's translations and puts the user's name in `custom`. CasaOS reads the
        // title as a map of languages, so a plain-string title becomes one.
        val title = x["title"].mutableMap()
            ?: LinkedHashMap<Any?, Any?>().also { m -> x["title"].scalar()?.let { m["en_us"] = it }; x["title"] = m }
        title["custom"] = new.title
    }
    // CasaOS expects these keys to be present, so cleared values are written as empty strings.
    if (new.icon != old.icon) x["icon"] = new.icon
    if (new.scheme != old.scheme) x["scheme"] = new.scheme
    if (new.hostname != old.hostname) x["hostname"] = new.hostname
    if (new.port != old.port) x["port_map"] = new.port
    if (new.path != old.path) x["index"] = new.path
}

private fun writeService(node: YamlMap, old: ServiceSettings, new: ServiceSettings) {
    if (new.image != old.image || new.tag != old.tag) node["image"] = joinImage(new.image, new.tag)
    if (new.containerName != old.containerName) node.putOrRemove("container_name", new.containerName)
    if (new.ports != old.ports) node.putOrRemove("ports", writePorts(node["ports"].list().orEmpty(), new.ports))
    if (new.volumes != old.volumes) node.putOrRemove("volumes", writeVolumes(node["volumes"].list().orEmpty(), new.volumes))
    if (new.environment != old.environment) {
        val pairs = new.environment.map { it.key to escapeEnv(it.value) }
        // Keep the style the file used.
        node.putOrRemove(
            "environment",
            if (node["environment"] is List<*>) pairs.map { (k, v) -> "$k=$v" } else LinkedHashMap<Any?, Any?>(pairs.toMap()),
        )
    }
    if (new.devices != old.devices) node.putOrRemove("devices", writeDevices(node["devices"].list().orEmpty(), new.devices))
    if (new.command != old.command) {
        // A command written as one string is run by a shell; keep it that way while it's still one entry.
        node.putOrRemove("command", if (node["command"] is String && new.command.size == 1) new.command[0] else new.command)
    }
    if (new.privileged != old.privileged) node.putOrRemove("privileged", new.privileged.takeIf { it })
    if (new.memoryMb != old.memoryMb) writeMemory(node, new.memoryMb)
    if (new.cpuShares != old.cpuShares) node.putOrRemove("cpu_shares", new.cpuShares)
    if (new.restart != old.restart) node.putOrRemove("restart", new.restart)
    if (new.capAdd != old.capAdd) node.putOrRemove("cap_add", new.capAdd)
}

private fun writePorts(originals: List<*>, ports: List<PortMapping>): List<Any> = ports.map { p ->
    val orig = p.origin?.let(originals::getOrNull)
    val target = p.container.toIntOrNull()
    if (target == null || orig is String || orig is Number) {
        // Short syntax: needed for ranges, and keeps an entry that was written that way (with its host IP).
        val ip = (orig as? String)?.substringBefore('/')?.split(':')?.takeIf { it.size == 3 }?.first()
        buildString {
            if (ip != null && p.host.isNotEmpty()) append(ip).append(':')
            if (p.host.isNotEmpty()) append(p.host).append(':')
            append(p.container)
            if (p.protocol != "tcp") append('/').append(p.protocol)
        }
    } else {
        LinkedHashMap<Any?, Any?>().apply {
            orig.map()?.let { putAll(it) }
            this["target"] = target
            if (p.host.isEmpty()) remove("published") else this["published"] = p.host
            this["protocol"] = p.protocol
        }
    }
}

/** A path is a folder on the server; anything else names a Docker volume. */
private fun volumeType(source: String) =
    if (source.startsWith("/") || source.startsWith(".") || source.startsWith("~")) "bind" else "volume"

private fun writeVolumes(originals: List<*>, volumes: List<VolumeMapping>): List<Any> = volumes.map { v ->
    when (val orig = v.origin?.let(originals::getOrNull)) {
        is String -> listOfNotNull(v.host.ifEmpty { null }, v.container, orig.split(':').getOrNull(2)).joinToString(":")
        else -> LinkedHashMap<Any?, Any?>().apply {
            orig.map()?.let { putAll(it) }
            val type = this["type"].scalar()
            if (type == null || type == "bind" || type == "volume") {
                val newType = if (v.host.isEmpty()) "volume" else volumeType(v.host)
                this["type"] = newType
                // Long syntax doesn't create a missing host folder unless asked; the short syntax CasaOS's
                // store apps use always does.
                if (newType == "bind") {
                    remove("volume")
                    (this["bind"].mutableMap() ?: LinkedHashMap<Any?, Any?>().also { this["bind"] = it }).putIfAbsent("create_host_path", true)
                } else {
                    remove("bind")
                }
            }
            if (v.host.isEmpty()) remove("source") else this["source"] = v.host
            this["target"] = v.container
        }.let { m ->
            // Keep `type` first, as compose-go writes it.
            LinkedHashMap<Any?, Any?>().apply { m["type"]?.let { this["type"] = it }; putAll(m) }
        }
    }
}

private fun writeDevices(originals: List<*>, devices: List<DeviceMapping>): List<Any> = devices.map { d ->
    val container = d.container.ifEmpty { d.host }
    when (val orig = d.origin?.let(originals::getOrNull)) {
        is Map<*, *> -> LinkedHashMap<Any?, Any?>(orig).apply { this["source"] = d.host; this["target"] = container }
        else -> listOfNotNull(d.host, container, (orig as? String)?.split(':')?.getOrNull(2)).joinToString(":")
    }
}

private fun writeMemory(node: YamlMap, mb: String) {
    // Edit whichever key the file uses; new limits go where the dashboard puts them.
    val deploy = node["deploy"].mutableMap()
    val resources = deploy?.get("resources").mutableMap()
    val limits = resources?.get("limits").mutableMap()
    if (node.containsKey("mem_limit") && limits?.get("memory") == null) {
        node.putOrRemove("mem_limit", mb.ifEmpty { null }?.let { "${it}M" })
        return
    }
    if (mb.isNotEmpty()) {
        node.child("deploy").child("resources").child("limits")["memory"] = "${mb}M"
        return
    }
    if (deploy == null || resources == null || limits == null) return
    limits.remove("memory")
    // Tidy up the blocks the limit was the only thing in.
    if (limits.isEmpty()) resources.remove("limits")
    if (resources.isEmpty()) deploy.remove("resources")
    if (deploy.isEmpty()) node.remove("deploy")
}

// endregion
