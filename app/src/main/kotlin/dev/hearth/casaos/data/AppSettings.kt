package dev.hearth.casaos.data

import androidx.compose.runtime.Immutable

/*
 * An app's settings, as the CasaOS dashboard's settings dialog shows them. They come from the compose
 * file CasaOS keeps for the app (see ComposeYaml.kt for reading and writing it).
 *
 * Every value is a String so a form can hold half-typed input; validate() says what's wrong before saving.
 */

@Immutable
data class AppSettings(
    val app: AppInfoSettings,
    /** The app's containers. The main one comes first. */
    val services: List<ServiceSettings>,
)

/** App-wide settings from CasaOS's `x-casaos` block: name, icon, and where the web UI is. */
@Immutable
data class AppInfoSettings(
    val title: String,
    val icon: String,
    val scheme: String,
    /** Empty means the CasaOS server's own address. */
    val hostname: String,
    val port: String,
    val path: String,
)

@Immutable
data class ServiceSettings(
    val name: String,
    /** Image without the tag, e.g. `linuxserver/jellyfin`. */
    val image: String,
    /** Empty means `latest`. */
    val tag: String,
    val containerName: String,
    /** Shown only: switching networks needs the YAML editor. */
    val network: String,
    val ports: List<PortMapping>,
    val volumes: List<VolumeMapping>,
    val environment: List<EnvVar>,
    val devices: List<DeviceMapping>,
    val command: List<String>,
    val privileged: Boolean,
    /** Whole MB, empty for no limit. */
    val memoryMb: String,
    /** CasaOS offers 10 (low), 50 (medium) and 90 (high). Null when unset. */
    val cpuShares: Int?,
    /** `no`, `always`, `on-failure`, `unless-stopped`, or empty when unset. */
    val restart: String,
    val capAdd: List<String>,
    /** Descriptions an App Store app gives its settings, e.g. port 8096 is "Web UI". */
    val hints: SettingHints = SettingHints(),
)

/*
 * `origin` is the entry's index in the compose file's list, or null for a row added in Hearth. Saving
 * writes an edited entry over its original, so options Hearth doesn't show (host_ip, bind options…) stay.
 */

@Immutable
data class PortMapping(val host: String, val container: String, val protocol: String = "tcp", val origin: Int? = null)

@Immutable
data class VolumeMapping(val host: String, val container: String, val readOnly: Boolean = false, val origin: Int? = null)

@Immutable
data class EnvVar(val key: String, val value: String)

@Immutable
data class DeviceMapping(val host: String, val container: String, val origin: Int? = null)

/** Keyed by the container side: port, path, variable name or device. */
@Immutable
data class SettingHints(
    val ports: Map<String, String> = emptyMap(),
    val volumes: Map<String, String> = emptyMap(),
    val environment: Map<String, String> = emptyMap(),
    val devices: Map<String, String> = emptyMap(),
)

val RestartPolicies = listOf("no", "always", "on-failure", "unless-stopped")

/** The dashboard's CPU share levels. */
val CpuShareLevels = listOf(10 to "Low", 50 to "Medium", 90 to "High")

/** Drops rows left completely empty and trims what must not carry spaces. Values the app reads are kept as typed. */
fun AppSettings.normalized(): AppSettings = copy(
    app = app.copy(
        icon = app.icon.trim(),
        hostname = app.hostname.trim(),
        port = app.port.trim(),
        path = app.path.trim(),
    ),
    services = services.map { s ->
        s.copy(
            image = s.image.trim(),
            tag = s.tag.trim(),
            containerName = s.containerName.trim(),
            ports = s.ports.map { it.copy(host = it.host.trim(), container = it.container.trim()) }
                .filterNot { it.host.isEmpty() && it.container.isEmpty() },
            volumes = s.volumes.map { it.copy(host = it.host.trim(), container = it.container.trim()) }
                .filterNot { it.host.isEmpty() && it.container.isEmpty() },
            environment = s.environment.map { it.copy(key = it.key.trim()) }
                .filterNot { it.key.isEmpty() && it.value.isEmpty() },
            devices = s.devices.map { it.copy(host = it.host.trim(), container = it.container.trim()) }
                .filterNot { it.host.isEmpty() && it.container.isEmpty() },
            command = s.command.filter { it.isNotEmpty() },
            memoryMb = s.memoryMb.trim(),
            capAdd = s.capAdd.map { it.trim() }.filter { it.isNotEmpty() },
        )
    },
)

/**
 * The settings with container [serviceIndex] switched to image tag [tag]. An image pinned by digest
 * (`name@sha256:…` or `name:1.0@sha256:…`) drops the digest, or the tag would be ignored, and its old tag.
 */
fun AppSettings.withImageTag(serviceIndex: Int, tag: String): AppSettings = copy(
    services = services.mapIndexed { i, s ->
        if (i == serviceIndex) s.copy(image = splitImage(s.image.substringBefore('@')).first, tag = tag) else s
    },
)

private val PortSpec = Regex("""\d{1,5}(-\d{1,5})?""")
private val ContainerNamePattern = Regex("""[a-zA-Z0-9][a-zA-Z0-9_.-]*""")
private val TagPattern = Regex("""[\w][\w.-]{0,127}""")

private fun validPort(spec: String) =
    PortSpec.matches(spec) && spec.split('-').all { it.toInt() in 1..65535 }

/** Problems that would make CasaOS reject the settings or break the app, in plain words. Empty when fine. */
fun AppSettings.validate(): List<String> {
    val problems = mutableListOf<String>()
    if (app.port.isNotEmpty() && !validPort(app.port)) problems += "Web UI port must be a number from 1 to 65535."
    for (s in services) {
        val where = if (services.size > 1) "${s.name}: " else ""
        if (s.image.isEmpty()) problems += "${where}Docker image can't be empty."
        if (s.tag.isNotEmpty() && !TagPattern.matches(s.tag)) problems += "${where}Tag \"${s.tag}\" isn't a valid image tag."
        if (s.containerName.isNotEmpty() && !ContainerNamePattern.matches(s.containerName)) {
            problems += "${where}Container name can only use letters, numbers, and _ . -"
        }
        for (p in s.ports) {
            if (p.container.isEmpty()) problems += "${where}Every port needs a container port."
            else if (!validPort(p.container)) problems += "${where}Container port \"${p.container}\" must be a number from 1 to 65535."
            if (p.host.isNotEmpty() && !validPort(p.host)) problems += "${where}Host port \"${p.host}\" must be a number from 1 to 65535."
        }
        for (v in s.volumes) {
            if (v.container.isEmpty()) problems += "${where}Every volume needs a container path."
            else if (!v.container.startsWith("/")) problems += "${where}Container path \"${v.container}\" must start with /."
        }
        for (e in s.environment) {
            if (e.key.isEmpty()) problems += "${where}Every environment variable needs a name."
            else if (e.key.any { it == '=' || it.isWhitespace() }) problems += "${where}Variable name \"${e.key}\" can't contain spaces or =."
        }
        for (d in s.devices) {
            if (d.host.isEmpty()) problems += "${where}Every device needs a host path."
        }
        if (s.memoryMb.isNotEmpty()) {
            val mb = s.memoryMb.toLongOrNull()
            // Docker refuses limits under 6 MB.
            if (mb == null || mb < 6) problems += "${where}Memory limit must be a whole number of MB, at least 6."
        }
    }
    return problems.distinct()
}

/** Environment variables whose values are hidden until tapped. */
private val SecretKey = Regex("(?i)pass|secret|token|key|pwd|auth|credential|cookie")

fun EnvVar.looksSecret(): Boolean = SecretKey.containsMatchIn(key) && value.isNotEmpty()
