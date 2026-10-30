package dev.hearth.casaos

import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.ImageRef
import dev.hearth.casaos.data.ImageRegistry
import dev.hearth.casaos.data.currentTag
import dev.hearth.casaos.data.isNoiseTag
import dev.hearth.casaos.data.parseImageRef
import dev.hearth.casaos.data.sortTagsNewestFirst
import dev.hearth.casaos.data.versionLabel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ImageRegistryTest {

    // region image references and tags

    @Test fun `image references resolve to a registry and repository the way Docker reads them`() {
        assertEquals(ImageRef("docker.io", "library/nginx"), parseImageRef("nginx"))
        assertEquals(ImageRef("docker.io", "library/nginx"), parseImageRef("nginx:1.27@sha256:abc"))
        assertEquals(ImageRef("docker.io", "linuxserver/jellyfin"), parseImageRef("linuxserver/jellyfin:10.9.1"))
        assertEquals(ImageRef("docker.io", "library/redis"), parseImageRef("docker.io/library/redis:7"))
        assertEquals(ImageRef("docker.io", "library/redis"), parseImageRef("index.docker.io/redis"))
        assertEquals(ImageRef("ghcr.io", "home-assistant/home-assistant"), parseImageRef("ghcr.io/home-assistant/home-assistant:stable"))
        // LinuxServer's own domain lists the same tags as their ghcr.io packages.
        assertEquals(ImageRef("ghcr.io", "linuxserver/sonarr"), parseImageRef("lscr.io/linuxserver/sonarr:latest"))
        assertEquals(ImageRef("localhost:5000", "team/app"), parseImageRef("localhost:5000/team/app:1"))
        assertEquals(ImageRef("registry.example.com:5000", "app"), parseImageRef("registry.example.com:5000/app@sha256:abc"))
        assertNull(parseImageRef(""))
    }

    @Test fun `display names drop docker hub's library prefix`() {
        assertEquals("nginx", parseImageRef("nginx")!!.display)
        assertEquals("linuxserver/jellyfin", parseImageRef("linuxserver/jellyfin")!!.display)
        assertEquals("ghcr.io/owner/app", parseImageRef("ghcr.io/owner/app:1")!!.display)
    }

    @Test fun `the current tag defaults to latest unless the image is pinned by digest`() {
        assertEquals("10.9.1", currentTag("linuxserver/jellyfin:10.9.1"))
        assertEquals("latest", currentTag("nginx"))
        assertEquals("5", currentTag("host:5000/app:5"))
        assertEquals("1.27", currentTag("nginx:1.27@sha256:abc"))
        assertNull(currentTag("nginx@sha256:0123456789abcdef0123"))
        assertEquals("sha256:0123456789ab", versionLabel("nginx@sha256:0123456789abcdef0123"))
        assertEquals("latest", versionLabel("nginx"))
    }

    @Test fun `signature and single-platform tags count as noise`() {
        assertTrue(isNoiseTag("sha256-3894d9eae884.sig"))
        assertTrue(isNoiseTag("sha256-3894d9eae884.att"))
        assertTrue(isNoiseTag("amd64-10.9.1"))
        assertTrue(isNoiseTag("arm64v8-latest"))
        assertTrue(isNoiseTag("arm32v7-version-20201102.25-unstable"))
        assertFalse(isNoiseTag("latest"))
        assertFalse(isNoiseTag("10.9.1"))
        assertFalse(isNoiseTag("version-12.4ubu2604"))
        assertFalse(isNoiseTag("armadillo")) // not a platform, just starts like one
    }

    @Test fun `tags without dates sort names first, then highest version first, then other builds, then noise`() {
        assertEquals(
            listOf("latest", "nightly", "stable", "v2.0", "1.10.1", "1.10", "1.9", "1.9-rc1", "feat-x", "develop-2.0.10", "develop-2.0.9", "amd64-1.0"),
            sortTagsNewestFirst(
                listOf("1.9", "develop-2.0.9", "stable", "feat-x", "amd64-1.0", "1.10", "latest", "1.9-rc1", "v2.0", "develop-2.0.10", "nightly", "1.10.1"),
            ),
        )
        // Released versions come before test builds, even of a higher version.
        assertEquals(
            listOf("2026.10.4", "2026.10.3", "2026.12.0.dev202610300219", "2026.11.0b1", "2026.11.0b0"),
            sortTagsNewestFirst(listOf("2026.11.0b0", "2026.10.3", "2026.12.0.dev202610300219", "2026.10.4", "2026.11.0b1")),
        )
        // A LinuxServer build of a version is newer than the version itself.
        assertEquals(listOf("10.9.1-ls12", "10.9.1"), sortTagsNewestFirst(listOf("10.9.1", "10.9.1-ls12")))
    }

    // endregion

    private lateinit var server: MockWebServer
    private lateinit var registry: ImageRegistry

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val base = server.url("/").toString().trimEnd('/')
        registry = ImageRegistry(hubApi = base, registryUrl = { base })
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private fun hubTag(name: String, digest: String, updated: String) = """
        {"name":"$name","digest":"$digest","last_updated":"$updated","full_size":999,
         "images":[{"architecture":"arm64","size":111},{"architecture":"amd64","size":342382698}]}
    """

    // region Docker Hub

    @Test fun `docker hub tags come newest first with dates, digests and the amd64 size`() = runTest {
        val next = server.url("/v2/repositories/library/nginx/tags?page=2&page_size=100&ordering=last_updated")
        server.enqueue(json("""{"count":3,"next":"$next","results":[${hubTag("latest", "sha256:aa", "2026-10-27T00:40:44.791632Z")},${hubTag("1.27.2", "sha256:aa", "2026-10-27T00:39:00Z")}]}"""))
        server.enqueue(json("""{"count":3,"next":null,"results":[${hubTag("1.27.1", "sha256:bb", "2026-09-01T00:00:00Z")}]}"""))

        val page = registry.tags(parseImageRef("nginx")!!)

        assertEquals(listOf("latest", "1.27.2", "1.27.1"), page.tags.map { it.name })
        assertEquals(Instant.parse("2026-10-27T00:40:44.791632Z"), page.tags[0].updatedAt)
        assertEquals("sha256:aa", page.tags[0].digest)
        assertEquals(342382698L, page.tags[0].sizeBytes)
        assertEquals(3, page.total)
        assertFalse(page.truncated)
        assertEquals("/v2/repositories/library/nginx/tags?page_size=100&ordering=last_updated", server.takeRequest().path)
        assertEquals("/v2/repositories/library/nginx/tags?page=2&page_size=100&ordering=last_updated", server.takeRequest().path)
    }

    @Test fun `docker hub stops after a few pages and says there are more`() = runTest {
        repeat(4) { i ->
            val next = server.url("/v2/repositories/a/b/tags?page=${i + 2}")
            server.enqueue(json("""{"count":13341,"next":"$next","results":[${hubTag("t$i", "sha256:$i", "2026-10-01T00:00:00Z")}]}"""))
        }
        val page = registry.tags(parseImageRef("a/b")!!)
        assertEquals(3, page.tags.size)
        assertTrue(page.truncated)
        assertEquals(13341, page.total)
        assertEquals(3, server.requestCount)
    }

    @Test fun `docker hub searches on the server`() = runTest {
        server.enqueue(json("""{"count":0,"next":null,"results":[]}"""))
        registry.tags(parseImageRef("linuxserver/jellyfin")!!, search = " 10.8 ")
        assertEquals(
            "/v2/repositories/linuxserver/jellyfin/tags?page_size=100&ordering=last_updated&name=10.8",
            server.takeRequest().path,
        )
    }

    @Test fun `a missing docker hub image says so`() = runTest {
        server.enqueue(json("""{"message":"httperror 404: object not found"}""", 404))
        val e = assertThrows(CasaException.Server::class.java) { runBlocking { registry.tags(parseImageRef("me/private")!!) } }
        assertEquals("Docker Hub has no public image called me/private.", e.message)
    }

    @Test fun `one docker hub tag can be looked up to see what latest points at`() = runTest {
        server.enqueue(json(hubTag("latest", "sha256:aa", "2026-10-27T00:40:44Z")))
        assertEquals("sha256:aa", registry.tag(parseImageRef("nginx")!!, "latest")!!.digest)
        assertEquals("/v2/repositories/library/nginx/tags/latest", server.takeRequest().path)

        server.enqueue(json("{}", 404))
        assertNull(registry.tag(parseImageRef("nginx")!!, "nope"))
        // Other registries don't say, so they aren't asked.
        assertNull(registry.tag(parseImageRef("ghcr.io/a/b")!!, "latest"))
        assertEquals(2, server.requestCount)
    }

    // endregion

    // region other registries

    @Test fun `other registries get an anonymous token from the challenge, then page through tags`() = runTest {
        val realm = server.url("/token")
        server.enqueue(
            json("""{"errors":[{"code":"UNAUTHORIZED"}]}""", 401)
                .addHeader("WWW-Authenticate", """Bearer realm="$realm",service="ghcr.io",scope="repository:linuxserver/jellyfin:pull""""),
        )
        server.enqueue(json("""{"token":"T0K"}"""))
        server.enqueue(
            json("""{"name":"linuxserver/jellyfin","tags":["10.8.13","amd64-10.9.1","latest"]}""")
                .addHeader("Link", """</v2/linuxserver/jellyfin/tags/list?last=latest&n=1000>; rel="next""""),
        )
        server.enqueue(json("""{"name":"linuxserver/jellyfin","tags":["10.9.1","10.10.0"]}"""))

        val page = registry.tags(parseImageRef("ghcr.io/linuxserver/jellyfin:latest")!!)

        assertEquals(listOf("latest", "10.10.0", "10.9.1", "10.8.13", "amd64-10.9.1"), page.tags.map { it.name })
        assertFalse(page.truncated)

        assertEquals("/v2/linuxserver/jellyfin/tags/list?n=1000", server.takeRequest().path)
        val token = server.takeRequest()
        assertEquals("/token?service=ghcr.io&scope=repository%3Alinuxserver%2Fjellyfin%3Apull", token.path)
        assertNull(token.getHeader("Authorization"))
        val first = server.takeRequest()
        assertEquals("Bearer T0K", first.getHeader("Authorization"))
        val second = server.takeRequest()
        assertEquals("/v2/linuxserver/jellyfin/tags/list?last=latest&n=1000", second.path)
        assertEquals("Bearer T0K", second.getHeader("Authorization"))
    }

    @Test fun `a token realm on another host over plain http is not followed`() = runTest {
        server.enqueue(
            json("{}", 401).addHeader("WWW-Authenticate", """Bearer realm="http://evil.example/token",service="x""""),
        )
        val e = assertThrows(CasaException.Server::class.java) { runBlocking { registry.tags(parseImageRef("quay.io/a/b")!!) } }
        assertEquals("quay.io wants a login to list quay.io/a/b's versions.", e.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun `a private image says it can't be listed`() = runTest {
        server.enqueue(json("{}", 401).addHeader("WWW-Authenticate", """Bearer realm="${server.url("/token")}",service="ghcr.io""""))
        server.enqueue(json("""{"token":"anon"}"""))
        server.enqueue(json("""{"errors":[{"code":"DENIED"}]}""", 403))
        val e = assertThrows(CasaException.Server::class.java) { runBlocking { registry.tags(parseImageRef("ghcr.io/me/secret")!!) } }
        assertEquals("ghcr.io/me/secret is private, so its versions can't be listed.", e.message)
    }

    @Test fun `an unreachable registry is a network error`() = runTest {
        server.shutdown()
        assertThrows(CasaException.Network::class.java) { runBlocking { registry.tags(parseImageRef("nginx")!!) } }
    }

    // endregion
}
