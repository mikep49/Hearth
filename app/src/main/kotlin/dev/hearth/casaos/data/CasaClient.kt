package dev.hearth.casaos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.serialization.json.JsonArray
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Talks to a CasaOS gateway. Auth is the raw JWT in the `Authorization` header (CasaOS does not use a
 * `Bearer` prefix). On a 401 the access token is refreshed once and the request retried.
 */
class CasaClient(
    private val session: Credentials,
    baseClient: OkHttpClient = OkHttpClient(),
    /** Told an app's id just before Hearth stops, restarts or recreates it, so alerts don't report that stop. */
    private val onAppStopping: (String) -> Unit = {},
) {

    private companion object {
        /**
         * Shared by every client: the app and a widget can hold clients for the same server, and two refreshes
         * of one login at once could leave each with a token the other just replaced.
         */
        val REFRESH_LOCK = Any()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonType = "application/json".toMediaType()
    private val yamlType = "application/yaml".toMediaType()

    /** No auth, no authenticator: used for login and for the refresh call itself. */
    private val plain: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()


    /** Shared with Coil so app icons and thumbnails go through the same auth handling. */
    val http: OkHttpClient = plain.newBuilder()
        .addInterceptor { chain ->
            val req = chain.request()
            val token = session.accessToken
            // Never attach the token to third-party hosts (e.g. icon CDNs).
            chain.proceed(if (token != null && isOurServer(req.url)) req.newBuilder().header("Authorization", token).build() else req)
        }
        .authenticator { _, response -> reauthenticate(response) }
        .build()

    // region auth

    suspend fun login(rawServer: String, username: String, password: String) {
        val base = normalizeBaseUrl(rawServer) ?: throw CasaException.Network("That doesn't look like a valid address")
        val body = buildJsonObject { put("username", username); put("password", password) }
        val req = Request.Builder()
            .url(base.toHttpUrl().newBuilder().addPathSegments("v1/users/login").build())
            .post(body.toString().toRequestBody(jsonType))
            .build()
        val data = execute(plain, req)
        val login = decode<LoginData>(data)
        session.signIn(base, login.user.username.ifBlank { username }, login.token)
    }

    private fun isOurServer(url: HttpUrl): Boolean =
        session.baseUrl?.toHttpUrl()?.let { it.host == url.host && it.port == url.port } == true

    private fun reauthenticate(response: Response): Request? {
        // A 401 from some other host must not trigger a refresh, and must never receive our token on retry.
        if (!isOurServer(response.request.url)) return null
        val path = response.request.url.encodedPath
        if (path.endsWith("/users/login") || path.endsWith("/users/refresh")) return null
        if (response.priorResponse != null) return null // already retried once
        synchronized(REFRESH_LOCK) {
            val sent = response.request.header("Authorization")
            val current = session.accessToken
            // Another request already refreshed while we waited on the lock: just retry with the new token.
            if (current != null && current != sent) return response.request.newBuilder().header("Authorization", current).build()

            val refresh = session.refreshToken ?: return signOutAndGiveUp()
            val base = session.baseUrl ?: return null
            val req = Request.Builder()
                .url(base.toHttpUrl().newBuilder().addPathSegments("v1/users/refresh").build())
                .post(buildJsonObject { put("refresh_token", refresh) }.toString().toRequestBody(jsonType))
                .build()
            return try {
                plain.newCall(req).execute().use { r ->
                    if (r.code == 401 || r.code == 403) return signOutAndGiveUp()
                    if (!r.isSuccessful) return null
                    val token = decode<TokenDto>(unwrap(r.body.string()))
                    session.updateTokens(token)
                    response.request.newBuilder().header("Authorization", token.accessToken).build()
                }
            } catch (_: IOException) {
                null // offline: surface the original 401 rather than signing the user out
            } catch (_: CasaException) {
                null
            }
        }
    }

    private fun signOutAndGiveUp(): Request? {
        session.signOut()
        return null
    }

    // endregion

    // region endpoints

    suspend fun utilization(): SystemStats {
        val data = get("v1", "sys", "utilization")
        val now = System.currentTimeMillis()
        return parsing { parseUtilization(data, now) }
    }

    /**
     * Every drive with its health and volumes. Slow-ish: CasaOS runs smartctl on each drive to answer.
     * USB drives come from a second call; if that one fails, the internal drives still show.
     */
    suspend fun drives(): List<Drive> {
        val disks = get("v1", "disks")
        val usb = try {
            get("v1", "disks", "usb")
        } catch (e: CasaException.Server) {
            null
        } catch (e: CasaException.Parse) {
            null
        }
        return parsing { parseDrives(disks, usb) }
    }

    suspend fun apps(): List<InstalledApp> {
        val data = get("v2", "app_management", "compose")
        return parsing { parseApps(data) }
    }

    suspend fun setAppStatus(id: String, action: AppAction) {
        if (action != AppAction.Start) appStopping(id)
        val req = request("v2", "app_management", "compose", id, "status")
            .put(JsonPrimitive(action.wire).toString().toRequestBody(jsonType))
            .build()
        execute(http, req)
    }

    suspend fun appLogs(id: String, lines: Int = 400): String {
        val url = urlOf("v2", "app_management", "compose", id, "logs") { addQueryParameter("lines", lines.toString()) }
        return (get(url) as? JsonPrimitive)?.content.orEmpty()
    }

    /**
     * The app's compose file, as the CasaOS dashboard's settings dialog loads it. CasaOS matches the
     * `Accept` header exactly, so it must be just `application/yaml`; anything else returns debug JSON.
     */
    suspend fun appComposeYaml(id: String): String {
        val req = request("v2", "app_management", "compose", id).header("Accept", "application/yaml").build()
        return withContext(Dispatchers.IO) {
            http.newCall(req).await().use { r ->
                val text = r.body.string()
                checkStatus(r.code, text)
                text
            }
        }
    }

    /**
     * Saves a new compose file for the app, like the dashboard's settings "Save". CasaOS checks it (and that
     * its ports are free) before replying, then recreates the containers in the background.
     */
    suspend fun applyAppSettings(id: String, composeYaml: String) {
        val url = urlOf("v2", "app_management", "compose", id) { addQueryParameter("check_port_conflict", "true") }
        appStopping(id)
        execute(http, Request.Builder().url(url).put(composeYaml.toRequestBody(yamlType)).build())
    }

    /**
     * The version the CasaOS App Store currently ships for a store app's main container, or null for apps
     * that didn't come from the store.
     */
    suspend fun storeStableTag(storeAppId: String): String? = try {
        ((get("v2", "app_management", "apps", storeAppId, "stable") as? JsonObject)?.get("tag") as? JsonPrimitive)
            ?.content?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: CasaException.Server) {
        null
    } catch (e: CasaException.Parse) {
        null
    }

    /**
     * Updates the app to the version the App Store now ships, like the dashboard's "Update". CasaOS downloads
     * the new image and recreates the containers in the background after replying.
     */
    suspend fun updateApp(id: String) {
        appStopping(id)
        execute(http, request("v2", "app_management", "compose", id).patch(ByteArray(0).toRequestBody(null)).build())
    }

    suspend fun folder(path: String): FolderListing {
        val url = urlOf("v1", "folder") {
            addQueryParameter("path", path)
            addQueryParameter("index", "1")
            addQueryParameter("size", "2000")
        }
        val data = get(url)
        return parsing { parseFolder(path, data) }
    }

    /** Creates a folder (and any missing parents), like the dashboard's "New folder". */
    suspend fun createFolder(path: String) {
        val body = buildJsonObject { put("path", path) }.toString().toRequestBody(jsonType)
        execute(http, request("v1", "folder").post(body).build())
    }

    /** Renames or moves one file or folder within the same disk. */
    suspend fun rename(oldPath: String, newPath: String) {
        val body = buildJsonObject { put("old_path", oldPath); put("new_path", newPath) }.toString().toRequestBody(jsonType)
        execute(http, request("v1", "file", "name").put(body).build())
    }

    /** Deletes files and folders (folders with everything in them). */
    suspend fun delete(paths: List<String>) {
        val body = JsonArray(paths.map(::JsonPrimitive)).toString().toRequestBody(jsonType)
        execute(http, request("v1", "file").delete(body).build())
    }

    /**
     * Copies or moves [paths] into the folder [to]. CasaOS replies at once and does the work in the background;
     * an item that already exists at the destination is skipped unless [overwrite].
     */
    suspend fun transfer(paths: List<String>, to: String, move: Boolean, overwrite: Boolean = false) {
        val body = buildJsonObject {
            put("type", if (move) "move" else "copy")
            put("to", to)
            put("style", if (overwrite) "overwrite" else "skip")
            put("item", JsonArray(paths.map { p -> buildJsonObject { put("from", p) } }))
        }
        execute(http, request("v1", "batch", "task").post(body.toString().toRequestBody(jsonType)).build())
    }

    /**
     * Uploads a file into the folder [dir], the way the dashboard's uploader does: in chunks, so a large video
     * doesn't need one huge request, and the server joins them once the last one arrives. An existing file with
     * the same name is replaced. [size] must be the exact length of [input].
     */
    suspend fun upload(
        dir: String,
        name: String,
        size: Long,
        input: InputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val totalChunks = if (size <= 0) 1 else ((size + UPLOAD_CHUNK - 1) / UPLOAD_CHUNK).toInt()
        val identifier = "$size-" + name.replace(Regex("[^0-9A-Za-z_-]"), "")
        val buf = ByteArray(UPLOAD_CHUNK)
        var sent = 0L
        for (chunk in 1..totalChunks) {
            // Fill the chunk fully: a stream may return fewer bytes than asked.
            var len = 0
            withContext(Dispatchers.IO) {
                while (len < buf.size) {
                    val n = input.read(buf, len, buf.size - len)
                    if (n < 0) break
                    len += n
                }
            }
            val part = buf.copyOf(len).toRequestBody("application/octet-stream".toMediaType())
            val form = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("chunkNumber", chunk.toString())
                .addFormDataPart("chunkSize", UPLOAD_CHUNK.toString())
                .addFormDataPart("currentChunkSize", len.toString())
                .addFormDataPart("totalSize", size.toString())
                .addFormDataPart("identifier", identifier)
                .addFormDataPart("filename", name)
                .addFormDataPart("relativePath", name)
                .addFormDataPart("totalChunks", totalChunks.toString())
                .addFormDataPart("path", dir)
                .addFormDataPart("file", name, part)
                .build()
            execute(uploads, request("v1", "file", "upload").post(form).build())
            sent += len
            onProgress(sent, size)
        }
    }

    /** Uploads wait for the server to write each chunk to disk, which can be slow on a busy USB drive. */
    private val uploads: OkHttpClient by lazy { http.newBuilder().writeTimeout(2, TimeUnit.MINUTES).readTimeout(2, TimeUnit.MINUTES).build() }

    /** Every app in the CasaOS App Store (and any third-party stores added on the server). */
    suspend fun storeApps(): List<StoreApp> {
        val data = get("v2", "app_management", "apps")
        return parsing { parseStoreApps(data) }
    }

    /**
     * Installs a store app, like its "Install" button in the dashboard: fetches the app's compose file from the
     * store and hands it back to CasaOS, which checks the ports and then downloads and starts it in the background.
     */
    suspend fun installStoreApp(storeAppId: String) {
        val get = request("v2", "app_management", "apps", storeAppId, "compose").header("Accept", "application/yaml").build()
        val yaml = withContext(Dispatchers.IO) {
            http.newCall(get).await().use { r ->
                val text = r.body.string()
                checkStatus(r.code, text)
                text
            }
        }
        val url = urlOf("v2", "app_management", "compose") { addQueryParameter("check_port_conflict", "true") }
        execute(http, Request.Builder().url(url).post(yaml.toRequestBody(yamlType)).build())
    }

    /** Removes an app and its containers. With [deleteData], its config folder under /DATA/AppData goes too. */
    suspend fun uninstallApp(id: String, deleteData: Boolean) {
        val url = urlOf("v2", "app_management", "compose", id) { addQueryParameter("delete_config_folder", deleteData.toString()) }
        execute(http, Request.Builder().url(url).delete().build())
    }

    suspend fun version(): String? =
        (get("v1", "sys", "version", "current") as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun power(action: PowerAction) {
        execute(http, request("v1", "sys", "state", action.wire).put(ByteArray(0).toRequestBody(null)).build())
    }

    /** Streams a file into [sink]. Returns the number of bytes written. */
    suspend fun download(path: String, sink: OutputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long {
        val url = urlOf("v1", "file") { addQueryParameter("path", path) }
        val call = http.newCall(Request.Builder().url(url).build())
        return withContext(Dispatchers.IO) {
            call.await().use { r ->
                checkStatus(r.code, null)
                val total = r.body.contentLength()
                var written = 0L
                r.body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive() // reading blocks, so a cancelled download has to be noticed here
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        written += n
                        onProgress(written, total)
                    }
                }
                written
            }
        }
    }

    /** Address of a file's contents, for image loaders and players. Fetch through [http] so it's signed in. */
    fun fileUrl(path: String): String = urlOf("v1", "file") { addQueryParameter("path", path) }.toString()

    /**
     * Headers a player that can't use [http] needs to stream [fileUrl]. Makes a cheap signed-in call first, so
     * an expired token is refreshed before it's handed over.
     */
    suspend fun authHeadersForStreaming(): Map<String, String> {
        version()
        return session.accessToken?.let { mapOf("Authorization" to it) }.orEmpty()
    }

    /** Up to [maxBytes] from the start of a file, for previews. */
    suspend fun readStart(path: String, maxBytes: Int): ByteArray {
        val call = http.newCall(Request.Builder().url(fileUrl(path)).build())
        return withContext(Dispatchers.IO) {
            call.await().use { r ->
                checkStatus(r.code, null)
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                r.body.byteStream().use { input ->
                    while (out.size() < maxBytes) {
                        val n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                out.toByteArray()
            }
        }
    }

    /**
     * A file's contents from byte [offset] on, for other apps reading it through Android's Files app. Asks for
     * just that range; if the server sends the whole file instead, the start is skipped. Blocks the calling
     * thread, and the caller closes the stream.
     */
    fun openFrom(path: String, offset: Long): InputStream {
        val req = Request.Builder().url(fileUrl(path)).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        val r = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw CasaException.Network(friendly(e), e)
        }
        try {
            if (r.code == 416) { // asked from the end of the file
                r.close()
                return java.io.ByteArrayInputStream(ByteArray(0))
            }
            checkStatus(r.code, null)
            val stream = r.body.byteStream()
            if (offset > 0 && r.code != 206 && skipFully(stream, offset) < offset) throw IOException("The file is shorter than expected")
            return stream
        } catch (t: Throwable) {
            r.close()
            throw if (t is IOException) CasaException.Network(friendly(t), t) else t
        }
    }

    // endregion

    // region plumbing

    /** Off the main thread: the listener saves to disk. */
    private suspend fun appStopping(id: String) = withContext(Dispatchers.IO) { onAppStopping(id) }

    private fun baseUrl(): HttpUrl = session.baseUrl?.toHttpUrl() ?: throw CasaException.Unauthorized()

    private fun urlOf(vararg segments: String, extra: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
        val b = baseUrl().newBuilder()
        segments.forEach { b.addPathSegment(it) } // encodes app ids that contain odd characters
        b.extra()
        return b.build()
    }

    private fun request(vararg segments: String): Request.Builder = Request.Builder().url(urlOf(*segments))

    private suspend fun get(vararg segments: String): JsonElement? = get(urlOf(*segments))

    private suspend fun get(url: HttpUrl): JsonElement? = execute(http, Request.Builder().url(url).build())

    /**
     * Callers resume on the main thread, and a folder can hold thousands of entries:
     * mapping and sorting them there stalls scrolling and screen transitions.
     */
    private suspend fun <T> parsing(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    /** Runs the call and returns the envelope's `data`. */
    private suspend fun execute(client: OkHttpClient, req: Request): JsonElement? = withContext(Dispatchers.IO) {
        client.newCall(req).await().use { r ->
            val text = r.body.string()
            checkStatus(r.code, text)
            unwrap(text)
        }
    }

    private fun checkStatus(code: Int, body: String?) {
        if (code in 200..299) return
        if (code == 401) throw CasaException.Unauthorized()
        throw CasaException.Server(code, messageFrom(body) ?: "Server returned $code")
    }

    private fun messageFrom(body: String?): String? = runCatching {
        val root = json.parseToJsonElement(body ?: return null) as? JsonObject ?: return null
        // A rejected app config says which ports clash in `data`; that's more useful than the bare message.
        validationMessage(root["data"]) ?: (root["message"] as? JsonPrimitive)?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Extracts `data`, raising if a v1-style envelope reports failure inside an HTTP 200. */
    internal fun unwrap(text: String): JsonElement? {
        if (text.isBlank()) return null
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return JsonPrimitive(text) // plain-text endpoints, e.g. /v1/sys/version/current
        }
        if (root !is JsonObject) return root
        val success = (root["success"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (success != null && success != 200) {
            throw CasaException.Server(success, (root["message"] as? JsonPrimitive)?.content ?: "Request failed")
        }
        return if ("data" in root || success != null) root["data"] else root
    }

    private inline fun <reified T> decode(el: JsonElement?): T = try {
        json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), el ?: throw CasaException.Parse("Empty response"))
    } catch (e: CasaException) {
        throw e
    } catch (e: Exception) {
        throw CasaException.Parse("Unexpected response from server", e)
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(CasaException.Network(friendly(e), e))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    private fun friendly(e: IOException): String = when (e) {
        is java.net.SocketTimeoutException -> "The server took too long to respond"
        is java.net.UnknownHostException -> "Couldn't find that server on the network"
        is java.net.ConnectException -> "Couldn't connect. Is the server on and reachable?"
        else -> e.message ?: "Network error"
    }

    // endregion
}

/** Matches the dashboard uploader's chunk size. */
private const val UPLOAD_CHUNK = 1024 * 1024
