package dev.hearth.casaos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Why something Hearth reads over SSH can't be shown. [needsSignIn] means the fix is signing in on the Terminal tab. */
class SshUnavailable(message: String, val needsSignIn: Boolean = false) : Exception(message)

/**
 * A login for reading what CasaOS's API doesn't report, made with the password or key the user saved on the
 * Terminal tab. Keeps one login open between readings; [close] it when done.
 */
class SavedSsh(
    private val profile: () -> SshProfile,
    private val hostKeys: HostKeyStore,
    /** What signing in is for, to end "Sign in on the Terminal tab … to": for example "see what each app uses". */
    private val purpose: String,
    private val connect: suspend (SshProfile, SshAuth, HostKeyStore) -> SshRunner = { p, auth, keys ->
        SshRunner.open(p.host, p.port, p.username, auth, keys)
    },
) {
    private var runner: SshRunner? = null
    private var openedWith: SshProfile? = null

    /** Set once plain `docker` is refused: the user isn't in the docker group, so commands go through sudo. */
    private var useSudo = false

    /** Runs [block] on the login, signing in first if it isn't open. Throws [SshUnavailable]. */
    suspend fun <T> read(block: suspend Commands.() -> T): T {
        val p = profile()
        val auth = p.savedAuth
        if (p.username.isBlank() || auth == null) {
            close()
            throw SshUnavailable(
                "Sign in on the Terminal tab with an SSH key, or with \"Remember password\" on, to $purpose.",
                needsSignIn = true,
            )
        }
        val ssh = runnerFor(p, auth)
        try {
            return Commands(ssh, auth).block()
        } catch (e: SshException) {
            close() // the login dropped: start a fresh one next time
            throw SshUnavailable(e.message ?: "Lost the SSH connection")
        }
    }

    private suspend fun runnerFor(p: SshProfile, auth: SshAuth): SshRunner {
        runner?.let { if (it.isOpen && openedWith == p) return it }
        close()
        val opened = try {
            connect(p, auth, hostKeys)
        } catch (e: SshException.UnknownHost) {
            throw SshUnavailable("Open the Terminal tab once to confirm this server's identity.", needsSignIn = true)
        } catch (e: SshException.HostKeyChanged) {
            throw SshUnavailable("The server's identity has changed. Check it on the Terminal tab.", needsSignIn = true)
        } catch (e: SshException.Auth) {
            val saved = if (auth is SshAuth.Key) "SSH key" else "SSH password"
            throw SshUnavailable("The saved $saved didn't work. Sign in again on the Terminal tab.", needsSignIn = true)
        } catch (e: SshException) {
            throw SshUnavailable(e.message ?: "Couldn't connect over SSH")
        }
        runner = opened
        openedWith = p
        useSudo = false
        return opened
    }

    /** What [read] can run on the server. */
    inner class Commands internal constructor(private val ssh: SshRunner, private val auth: SshAuth) {
        /** Runs [command] as the SSH user, and returns what it printed and how it exited. */
        suspend fun run(command: String): SshRunner.Result = ssh.run(command)

        /** Runs `docker` with [args] and returns its output, through sudo if the SSH user isn't in the docker group. */
        suspend fun docker(args: String): String {
            if (!useSudo) {
                val r = ssh.run("docker $args")
                if (r.exitCode == 0) return r.stdout
                if (r.exitCode == 127) throw SshUnavailable("Docker isn't installed on this server.")
                if (!r.stderr.contains("permission denied", ignoreCase = true)) throw SshUnavailable(dockerError(r))
                useSudo = true
            }
            val r = when (auth) {
                // -S reads the password from input; -p '' keeps the prompt out of the output.
                is SshAuth.Password -> ssh.run("sudo -S -p '' docker $args", stdin = auth.password + "\n")
                // A key gives sudo no password to check, so this only works where sudo doesn't ask for one.
                is SshAuth.Key -> ssh.run("sudo -n docker $args")
            }
            if (r.exitCode == 0) return r.stdout
            throw SshUnavailable(
                when (auth) {
                    is SshAuth.Password -> "Your SSH user isn't allowed to use Docker. Sign in as an admin user on the Terminal tab."
                    is SshAuth.Key -> "Your SSH user isn't allowed to use Docker. Add it to the docker group in the terminal, then sign in again: sudo usermod -aG docker \$USER"
                },
            )
        }
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
