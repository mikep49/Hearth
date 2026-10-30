package dev.hearth.casaos.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/*
 * Lists the versions (tags) a Docker image has, for picking which one an app runs. CasaOS has no endpoint
 * for this, so the phone asks the image's registry itself: Docker Hub's own API (newest first, with dates
 * and sizes), or the standard registry API anywhere else (ghcr.io, quay.io…), which gives names only.
 *
 * Only public images can be listed. Nothing from the CasaOS session is ever sent to a registry.
 */

/** Where an image lives: `docker.io` + `library/nginx`, `ghcr.io` + `linuxserver/jellyfin`… */
@Immutable
data class ImageRef(val registry: String, val repository: String) {
    val isDockerHub: Boolean get() = registry == DOCKER_HUB

    /** How people write it: `nginx`, `linuxserver/jellyfin`, `ghcr.io/owner/app`. */
    val display: String
        get() = when {
            !isDockerHub -> "$registry/$repository"
            repository.startsWith("library/") -> repository.removePrefix("library/")
            else -> repository
        }

    companion object {
        const val DOCKER_HUB = "docker.io"
    }
}

/**
 * Reads an image reference without its tag or digest. The first part names a registry only if it looks
 * like a host (has a `.` or `:`, or is `localhost`), as Docker decides it.
 */
fun parseImageRef(image: String): ImageRef? {
    val name = splitImage(image.trim().substringBefore('@')).first.trim('/')
    if (name.isEmpty() || name.any { it.isWhitespace() }) return null
    val first = name.substringBefore('/')
    val hasRegistry = '/' in name && ('.' in first || ':' in first || first == "localhost")
    var registry = if (hasRegistry) first.lowercase() else ImageRef.DOCKER_HUB
    var repo = if (hasRegistry) name.substringAfter('/') else name
    when (registry) {
        "index.docker.io", "registry-1.docker.io", "registry.hub.docker.com", "hub.docker.com" -> registry = ImageRef.DOCKER_HUB
        // LinuxServer's lscr.io serves the same images as their ghcr.io packages.
        "lscr.io" -> registry = "ghcr.io"
    }
    if (registry == ImageRef.DOCKER_HUB && '/' !in repo) repo = "library/$repo"
    return ImageRef(registry, repo.lowercase())
}

/**
 * The tag an image reference runs: `10.9.1` for `jellyfin/jellyfin:10.9.1`, `latest` when none is given.
 * Null for an image pinned only by digest (`name@sha256:…`), which has no tag.
 */
fun currentTag(image: String): String? {
    val tag = splitImage(image.substringBefore('@')).second
    return tag.ifEmpty { if ('@' in image) null else "latest" }
}

/** What to call the version an image reference runs: its tag, or a short digest when it's pinned by one. */
fun versionLabel(image: String): String =
    currentTag(image) ?: image.substringAfter('@').let { if (it.startsWith("sha256:")) it.take(19) else it }

@Immutable
data class ImageTag(
    val name: String,
    /** When the tag was last pushed. Only Docker Hub says. */
    val updatedAt: Instant? = null,
    /** Content digest; two tags with the same digest are the same image. Only Docker Hub says. */
    val digest: String? = null,
    /** Compressed download size. Only Docker Hub says. */
    val sizeBytes: Long? = null,
)

/**
 * Tags nobody picks by hand: signatures and attestations (`sha256-….sig`) and single-platform builds
 * (`amd64-1.2`, `arm64v8-latest`). The plain tag already picks the right platform for the server.
 */
fun isNoiseTag(tag: String): Boolean {
    val t = tag.lowercase()
    return t.startsWith("sha256-") || t.endsWith(".sig") || t.endsWith(".att") || t.endsWith(".sbom") ||
        PlatformPrefix.containsMatchIn(t)
}

private val PlatformPrefix = Regex("^(amd64|x86_64|x86-64|arm64v8|arm64|aarch64|arm32v[5-7]|armhf|armv7|armel|i386|386|ppc64le|s390x|riscv64)[-_]")

/** Tags that are a version number (`1.2.3`, `v10.9`, `2024.10.1-ls12`) rather than a name like `latest`. */
fun isVersionTag(tag: String): Boolean = VersionStart.containsMatchIn(tag)

private val VersionStart = Regex("^[vV]?\\d")

/**
 * Newest first, for registries that don't say when a tag was pushed: plain names like `latest` and
 * `stable` first, then released versions from highest to lowest, comparing the numbers in them as numbers
 * (10.10 > 10.9), then pre-releases (`2026.11.0b1`, `1.2.0.dev2026`), then other builds (`develop-2.0.1`, `feat-x`),
 * then noise.
 */
fun sortTagsNewestFirst(tags: List<String>): List<String> =
    tags.sortedWith(
        compareBy<String> { tagGroup(it) }
            .thenBy { it != "latest" }
            .thenComparator { a, b -> if (tagGroup(a) == 0) a.compareTo(b) else -compareVersions(a, b) },
    )

private fun tagGroup(tag: String): Int = when {
    isNoiseTag(tag) -> 4
    isVersionTag(tag) -> if (isPreRelease(tag)) 2 else 1
    // One-word channels (`latest`, `stable`, `main`); branch builds like `feat-docker-action` go with the other builds.
    tag.all { it.isLetter() } -> 0
    else -> 3
}

/** `1.2-rc1`, `2026.11.0b1`, `1.2.0.dev2026`, `3.0-alpha`: test builds of a version. */
fun isPreRelease(tag: String): Boolean = PreReleaseAnywhere.containsMatchIn(tag)

private val PreReleaseAnywhere = Regex("""(?i)(rc|alpha|beta|pre|preview|dev|snapshot|nightly|unstable)|\d[ab]\d""")

/**
 * Compares tags chunk by chunk, numbers numerically: `1.9` < `1.10`, `v2` < `v10`. A longer tag is newer
 * (`10.9.1-ls12` is a later build of `10.9.1`) unless the extra part marks a pre-release (`1.9-rc1` < `1.9`).
 */
fun compareVersions(a: String, b: String): Int {
    val x = VersionChunk.findAll(a.removePrefix("v").removePrefix("V")).map { it.value }.toList()
    val y = VersionChunk.findAll(b.removePrefix("v").removePrefix("V")).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]
        val q = y[i]
        val c = if (p[0].isDigit() && q[0].isDigit()) {
            val byLength = p.trimStart('0').length.compareTo(q.trimStart('0').length)
            if (byLength != 0) byLength else p.trimStart('0').compareTo(q.trimStart('0'))
        } else {
            p.compareTo(q)
        }
        if (c != 0) return c
    }
    if (x.size == y.size) return 0
    val longerIsPreRelease = PreRelease.containsMatchIn((if (x.size > y.size) x else y).drop(minOf(x.size, y.size)).joinToString(""))
    return if ((x.size > y.size) != longerIsPreRelease) 1 else -1
}

private val PreRelease = Regex("(?i)^[-._~+]?(rc|alpha|beta|pre|preview|dev|snapshot|nightly)")

private val VersionChunk = Regex("\\d+|[^\\d]+")

/** One page of an image's tags. */
data class TagPage(
    val tags: List<ImageTag>,
    /** Whether the registry has more than were fetched. */
    val truncated: Boolean,
    /** How many tags the image has in total, if the registry says. */
    val total: Int? = null,
)

class ImageRegistry(
    baseClient: OkHttpClient = OkHttpClient(),
    /** Overridable for tests. */
    private val hubApi: String = "https://hub.docker.com",
    private val registryUrl: (registry: String) -> String = { "https://$it" },
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A client of its own, with no CasaOS token in it. */
    private val http: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * The image's tags, newest first. On Docker Hub, [search] narrows them on the server (the newest few hundred
     * of an image with thousands of tags may not include an old version you're looking for); elsewhere every
     * tag is fetched and [search] is left to the caller.
     */
    suspend fun tags(image: ImageRef, search: String = ""): TagPage = withContext(Dispatchers.IO) {
        try {
            if (image.isDockerHub) hubTags(image, search.trim()) else registryTags(image)
        } catch (e: IOException) {
            throw CasaException.Network(
                when (e) {
                    is java.net.UnknownHostException -> "Couldn't reach ${hostOf(image)}. Is the phone online?"
                    is java.net.SocketTimeoutException -> "${hostOf(image)} took too long to respond"
                    else -> e.message ?: "Network error"
                },
                e,
            )
        }
    }

    /** One tag's details, to say what a moving tag like `latest` points at. Docker Hub only. */
    suspend fun tag(image: ImageRef, name: String): ImageTag? = withContext(Dispatchers.IO) {
        if (!image.isDockerHub) return@withContext null
        try {
            val url = hubRepoUrl(image).newBuilder().addPathSegment("tags").addPathSegment(name).build()
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                parseHubTag(json.parseToJsonElement(r.body.string()))
            }
        } catch (_: IOException) {
            null
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        }
    }

    private fun hostOf(image: ImageRef) = if (image.isDockerHub) "Docker Hub" else image.registry

    // region Docker Hub

    private fun hubRepoUrl(image: ImageRef): HttpUrl {
        val (namespace, repo) = image.repository.split('/', limit = 2).let { if (it.size == 2) it[0] to it[1] else "library" to it[0] }
        return hubApi.toHttpUrl().newBuilder()
            .addPathSegments("v2/repositories").addPathSegment(namespace).addPathSegment(repo)
            .build()
    }

    private fun hubTags(image: ImageRef, search: String): TagPage {
        var url: HttpUrl? = hubRepoUrl(image).newBuilder()
            .addPathSegment("tags")
            .addQueryParameter("page_size", "100")
            .addQueryParameter("ordering", "last_updated")
            .apply { if (search.isNotEmpty()) addQueryParameter("name", search) }
            .build()
        val tags = mutableListOf<ImageTag>()
        var total: Int? = null
        var pages = 0
        while (url != null && pages < HUB_PAGES) {
            val root = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                when {
                    r.code == 404 -> throw CasaException.Server(404, "Docker Hub has no public image called ${image.display}.")
                    r.code == 429 -> throw CasaException.Server(429, "Docker Hub is limiting requests. Try again in a minute.")
                    !r.isSuccessful -> throw CasaException.Server(r.code, "Docker Hub returned ${r.code}")
                }
                parseJson(r.body.string()) as? JsonObject ?: throw CasaException.Parse("Unexpected reply from Docker Hub")
            }
            total = (root["count"] as? JsonPrimitive)?.longOrNull?.toInt()
            (root["results"] as? JsonArray).orEmpty().mapNotNullTo(tags, ::parseHubTag)
            url = (root["next"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.toHttpUrlOrNull()
            pages++
        }
        return TagPage(tags.distinctBy { it.name }, truncated = url != null, total = total)
    }

    private fun parseHubTag(el: JsonElement): ImageTag? {
        val o = el as? JsonObject ?: return null
        val name = (o["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: return null
        val images = (o["images"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // What the server downloads: the amd64 image when there is one, as most CasaOS boxes are.
        val size = images.firstOrNull { (it["architecture"] as? JsonPrimitive)?.content == "amd64" }?.get("size")
            ?: o["full_size"]
        return ImageTag(
            name = name,
            updatedAt = ((o["last_updated"] ?: o["tag_last_pushed"]) as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it) }.getOrNull() },
            digest = (o["digest"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("sha256:") },
            sizeBytes = (size as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
        )
    }

    // endregion

    // region other registries

    /*
     * The registry API (`GET /v2/<name>/tags/list`) returns names only, in no useful order, and paged with a
     * `Link` header. Public images still want a token: the 401 says where to get an anonymous one.
     */
    private fun registryTags(image: ImageRef): TagPage {
        val base = registryUrl(image.registry).toHttpUrlOrNull()
            ?: throw CasaException.Parse("\"${image.registry}\" isn't a registry address")
        var url: HttpUrl? = base.newBuilder()
            .addPathSegment("v2").addPathSegments(image.repository).addPathSegments("tags/list")
            .addQueryParameter("n", "1000")
            .build()
        var token: String? = null
        val names = mutableListOf<String>()
        var pages = 0
        while (url != null && pages < REGISTRY_PAGES) {
            var response = http.newCall(registryRequest(url, token)).execute()
            if (response.code == 401 && token == null) {
                val challenge = response.header("WWW-Authenticate")
                response.close()
                token = challenge?.let { anonymousToken(it, image) }
                    ?: throw CasaException.Server(401, "${image.registry} wants a login to list ${image.display}'s versions.")
                response = http.newCall(registryRequest(url, token)).execute()
            }
            val (tags, next) = response.use { r ->
                when {
                    r.code == 401 || r.code == 403 -> throw CasaException.Server(r.code, "${image.display} is private, so its versions can't be listed.")
                    r.code == 404 -> throw CasaException.Server(404, "${image.registry} has no image called ${image.repository}.")
                    r.code == 429 -> throw CasaException.Server(429, "${image.registry} is limiting requests. Try again in a minute.")
                    !r.isSuccessful -> throw CasaException.Server(r.code, "${image.registry} returned ${r.code}")
                }
                val root = parseJson(r.body.string()) as? JsonObject ?: throw CasaException.Parse("Unexpected reply from ${image.registry}")
                val tags = (root["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                tags to nextLink(r.header("Link"), url)
            }
            names += tags
            // Some registries repeat the last page forever; stop when a page adds nothing.
            url = next?.takeIf { tags.isNotEmpty() }
            pages++
        }
        return TagPage(sortTagsNewestFirst(names.distinct()).map { ImageTag(it) }, truncated = url != null)
    }

    private fun registryRequest(url: HttpUrl, token: String?) = Request.Builder().url(url)
        .header("Accept", "application/json")
        .apply { if (token != null) header("Authorization", "Bearer $token") }
        .build()

    /** Follows `WWW-Authenticate: Bearer realm="…",service="…",scope="…"` to an anonymous pull token. */
    private fun anonymousToken(challenge: String, image: ImageRef): String? {
        if (!challenge.trimStart().startsWith("Bearer", ignoreCase = true)) return null
        val params = ChallengeParam.findAll(challenge).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val realm = params["realm"]?.toHttpUrlOrNull() ?: return null
        // Only over HTTPS, so a token never crosses the network in the clear (tests use plain http locally).
        if (!realm.isHttps && realm.host != registryUrl(image.registry).toHttpUrlOrNull()?.host) return null
        val url = realm.newBuilder()
            .apply { params["service"]?.let { addQueryParameter("service", it) } }
            .addQueryParameter("scope", params["scope"] ?: "repository:${image.repository}:pull")
            .build()
        return http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = parseJson(r.body.string()) as? JsonObject ?: return null
            ((o["token"] ?: o["access_token"]) as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        }
    }

    private fun nextLink(header: String?, current: HttpUrl): HttpUrl? {
        val target = header?.let { LinkNext.find(it)?.groupValues?.get(1) } ?: return null
        return current.resolve(target)
    }

    // endregion

    private fun parseJson(text: String): JsonElement? = try {
        json.parseToJsonElement(text)
    } catch (e: kotlinx.serialization.SerializationException) {
        null
    }

    private companion object {
        /** 300 tags: enough to cover the last few releases of even very busy images. */
        const val HUB_PAGES = 3
        const val REGISTRY_PAGES = 10
        val ChallengeParam = Regex("""(\w+)="([^"]*)"""")
        val LinkNext = Regex("""<([^>]+)>\s*;\s*rel="?next"?""")
    }
}
