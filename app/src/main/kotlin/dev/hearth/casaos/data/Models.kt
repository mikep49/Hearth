package dev.hearth.casaos.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** CasaOS v1 returns `{success, message, data}`; v2 returns `{message, data}` and relies on HTTP status. */
@Serializable
data class Envelope<T>(
    val success: Int? = null,
    val message: String? = null,
    val data: T? = null,
)

@Serializable
data class TokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long = 0,
)

@Serializable
data class UserDto(
    val username: String = "",
    val nickname: String? = null,
    val role: String? = null,
)

@Serializable
data class LoginData(val token: TokenDto, val user: UserDto = UserDto())

data class DiskStats(val totalBytes: Long, val usedBytes: Long) {
    val percent: Double get() = if (totalBytes > 0) usedBytes * 100.0 / totalBytes else 0.0
}

/** One sample of `/v1/sys/utilization`. Network counters are cumulative; rates are derived by the caller. */
data class SystemStats(
    val cpuPercent: Double,
    val cpuCores: Int,
    val cpuTempC: Int?,
    val cpuModel: String,
    val memTotal: Long,
    val memUsed: Long,
    val memPercent: Double,
    val disk: DiskStats?,
    val netRxBytes: Long,
    val netTxBytes: Long,
    val sampledAtMs: Long,
)

enum class AppState { Running, Stopped, Restarting, Unknown }

/** Immutable so app rows skip recomposing when a refresh returns the same app unchanged. */
@Immutable
data class InstalledApp(
    val id: String,
    val name: String,
    val tagline: String?,
    val category: String?,
    val iconUrl: String?,
    val state: AppState,
    val rawStatus: String,
    val updateAvailable: Boolean,
    /** Host port of the web UI, if the app has one. */
    val webPort: String?,
    val webPath: String,
    val scheme: String,
)

enum class AppAction(val wire: String) { Start("start"), Stop("stop"), Restart("restart") }

data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: String?,
)

data class FolderListing(val path: String, val entries: List<FileEntry>)

enum class PowerAction(val wire: String) { Restart("restart"), Shutdown("off") }

sealed class CasaException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized(message: String = "Session expired. Please sign in again.") : CasaException(message)
    class Network(message: String, cause: Throwable? = null) : CasaException(message, cause)
    class Server(val code: Int, message: String) : CasaException(message)
    class Parse(message: String, cause: Throwable? = null) : CasaException(message, cause)
}
