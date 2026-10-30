package dev.hearth.casaos.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
    val containers: Int,
)

/** Why per-app usage can't be shown. [needsSignIn] means the fix is signing in on the Terminal tab. */
class UsageUnavailable(message: String, val needsSignIn: Boolean = false) : Exception(message)

/**
 * CasaOS's API only reports whole-server totals, so per-app figures come from Docker over SSH, using the
 * sign-in the user saved on the Terminal tab. Keeps one login open between samples; [close] it when done.
 */
class AppUsageSource(
    private val profile: () -> SshProfile,
    private val hostKeys: HostKeyStore,
    private val connect: suspend (SshProfile, String, HostKeyStore) -> SshRunner = { p, pw, keys ->
        SshRunner.open(p.host, p.port, p.username, pw, keys)
    },
) {
    private var runner: SshRunner? = null
    private var openedWith: SshProfile? = null

    /** Set once plain `docker` is refused: the user isn't in the docker group, so commands go through sudo. */
    private var useSudo = false

    /** Throws [UsageUnavailable]. */
    suspend fun sample(): List<ContainerUsage> {
        val p = profile()
        val password = p.password
        if (p.username.isBlank() || password == null) {
            close()
            throw UsageUnavailable("Sign in on the Terminal tab with \"Remember password\" on to see what each app uses.", needsSignIn = true)
        }
        val ssh = runnerFor(p, password)
        try {
            val owners = docker(ssh, password, "ps --format '{{.Names}}|{{.Label \"com.docker.compose.project\"}}'")
            val stats = docker(ssh, password, "stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}'")
            return parseDockerUsage(owners, stats)
        } catch (e: SshException) {
            close() // the login dropped: start a fresh one next time
            throw UsageUnavailable(e.message ?: "Lost the SSH connection")
        }
    }

    private suspend fun runnerFor(p: SshProfile, password: String): SshRunner {
        runner?.let { if (it.isOpen && openedWith == p) return it }
        close()
        val opened = try {
            connect(p, password, hostKeys)
        } catch (e: SshException.UnknownHost) {
            throw UsageUnavailable("Open the Terminal tab once to confirm this server's identity.", needsSignIn = true)
        } catch (e: SshException.HostKeyChanged) {
            throw UsageUnavailable("The server's identity has changed. Check it on the Terminal tab.", needsSignIn = true)
        } catch (e: SshException.Auth) {
            throw UsageUnavailable("The saved SSH password didn't work. Sign in again on the Terminal tab.", needsSignIn = true)
        } catch (e: SshException) {
            throw UsageUnavailable(e.message ?: "Couldn't connect over SSH")
        }
        runner = opened
        openedWith = p
        useSudo = false
        return opened
    }

    private suspend fun docker(ssh: SshRunner, password: String, args: String): String {
        if (!useSudo) {
            val r = ssh.run("docker $args")
            if (r.exitCode == 0) return r.stdout
            if (r.exitCode == 127) throw UsageUnavailable("Docker isn't installed on this server.")
            if (!r.stderr.contains("permission denied", ignoreCase = true)) throw UsageUnavailable(dockerError(r))
            useSudo = true
        }
        // -S reads the password from input; -p '' keeps the prompt out of the output.
        val r = ssh.run("sudo -S -p '' docker $args", stdin = password + "\n")
        if (r.exitCode == 0) return r.stdout
        throw UsageUnavailable("Your SSH user isn't allowed to read Docker's stats. Sign in as an admin user on the Terminal tab.")
    }

    private fun dockerError(r: SshRunner.Result) =
        r.stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { "Docker: $it" } ?: "Docker didn't answer"

    suspend fun close() {
        val r = runner ?: return
        runner = null
        openedWith = null
        withContext(NonCancellable + Dispatchers.IO) { r.close() }
    }
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
                containers = list.size,
            )
        }
        .sortedByDescending { it.cpuPercent }
}
