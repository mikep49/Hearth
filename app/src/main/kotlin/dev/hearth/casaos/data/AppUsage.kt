package dev.hearth.casaos.data

import androidx.compose.runtime.Immutable

/** One running container as `docker stats` reports it. [cpuPercent] is Docker's figure: 100 means one full core. */
data class ContainerUsage(val name: String, val project: String?, val cpuPercent: Double, val memBytes: Long)

/** What one installed app is using right now, summed over its containers. */
@Immutable
data class AppUsage(
    val id: String,
    val name: String,
    val iconUrl: String?,
    /** 100 means one full core, like Docker; divide by the core count for a share of the whole server. */
    val cpuPercent: Double,
    val memBytes: Long,
    /** The containers this is the sum of, busiest first. */
    val containers: List<ContainerUsage>,
)

/**
 * CasaOS's API only reports whole-server totals, so per-app figures come from Docker over SSH, using the
 * password or key the user saved on the Terminal tab. Keeps one login open between samples; [close] it when done.
 */
class AppUsageSource(profile: () -> SshProfile, hostKeys: HostKeyStore) {
    private val ssh = SavedSsh(profile, hostKeys, "see what each app uses")

    /** Throws [SshUnavailable]. */
    suspend fun sample(): List<ContainerUsage> = ssh.read {
        val owners = docker("ps --format '{{.Names}}|{{.Label \"com.docker.compose.project\"}}'")
        val stats = docker("stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}'")
        parseDockerUsage(owners, stats)
    }

    suspend fun close() = ssh.close()
}

/**
 * Joins `docker ps` (container|compose project) with `docker stats` (container|CPU%|memory). Containers that
 * appear in only one of them (started or stopped between the two calls) are skipped or left without a project.
 */
fun parseDockerUsage(psOutput: String, statsOutput: String): List<ContainerUsage> {
    val projects = psOutput.lineSequence()
        .mapNotNull { line -> line.trim().split('|').takeIf { it.size >= 2 && it[0].isNotEmpty() } }
        .associate { it[0] to it[1].trim().ifEmpty { null } }
    return statsOutput.lineSequence().mapNotNull { line ->
        val parts = line.trim().split('|')
        if (parts.size < 3 || parts[0].isEmpty()) return@mapNotNull null
        ContainerUsage(
            name = parts[0],
            project = projects[parts[0]],
            cpuPercent = parts[1].trim().removeSuffix("%").toDoubleOrNull() ?: 0.0, // "--" while a container starts
            memBytes = parseDockerSize(parts[2].substringBefore('/')) ?: 0L,
        )
    }.toList()
}

/** Reads sizes as Docker prints them: `512KiB`, `1.5GiB`, `10MB`, `0B`. */
fun parseDockerSize(text: String): Long? {
    val m = Regex("""^\s*([0-9]*\.?[0-9]+)\s*([A-Za-z]*)\s*$""").find(text) ?: return null
    val n = m.groupValues[1].toDoubleOrNull() ?: return null
    val unit = when (m.groupValues[2].lowercase()) {
        "", "b" -> 1.0
        "kib" -> 1024.0
        "mib" -> 1024.0 * 1024
        "gib" -> 1024.0 * 1024 * 1024
        "tib" -> 1024.0 * 1024 * 1024 * 1024
        "kb" -> 1e3
        "mb" -> 1e6
        "gb" -> 1e9
        "tb" -> 1e12
        else -> return null
    }
    return (n * unit).toLong()
}

/**
 * Sums containers into the CasaOS apps they belong to (CasaOS names each app's compose project after the
 * app's id). A container from outside CasaOS gets its own row under its container name. Busiest first.
 */
fun groupUsage(containers: List<ContainerUsage>, apps: List<InstalledApp>): List<AppUsage> {
    val byId = apps.associateBy { it.id }
    return containers
        .groupBy { c -> c.project?.takeIf { it in byId } ?: c.name.takeIf { it in byId } ?: c.project ?: c.name }
        .map { (key, list) ->
            val app = byId[key]
            AppUsage(
                id = key,
                name = app?.name ?: key,
                iconUrl = app?.iconUrl,
                cpuPercent = list.sumOf { it.cpuPercent },
                memBytes = list.sumOf { it.memBytes },
                containers = list.sortedByDescending { it.cpuPercent },
            )
        }
        .sortedByDescending { it.cpuPercent }
}
