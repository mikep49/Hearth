package dev.hearth.casaos

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.Credentials
import dev.hearth.casaos.data.TokenDto
import dev.hearth.casaos.widget.StatsLayout
import dev.hearth.casaos.widget.WidgetStatsStore
import dev.hearth.casaos.widget.WidgetStorage
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private class SignedIn(override var baseUrl: String?) : Credentials {
    override var accessToken: String? = "A1"
    override var refreshToken: String? = "R1"
    override fun signIn(baseUrl: String, username: String, token: TokenDto) { this.baseUrl = baseUrl }
    override fun updateTokens(token: TokenDto) = Unit
    override fun signOut() { baseUrl = null; accessToken = null; refreshToken = null }
}

private class MemoryStorage(override var saved: String? = null) : WidgetStorage

class WidgetStatsTest {
    private lateinit var server: MockWebServer
    private lateinit var creds: SignedIn
    private lateinit var storage: MemoryStorage
    private var clock = 1_000_000L

    private val utilization =
        """{"success":200,"data":{"cpu":{"percent":42,"num":4},"mem":{"total":1000,"used":250},"sys_disk":{"size":2000,"used":500}}}"""

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        creds = SignedIn("http://${server.hostName}:${server.port}")
        storage = MemoryStorage()
    }

    @After fun tearDown() = server.shutdown()

    private fun store() = WidgetStatsStore(storage, CasaClient(creds), creds) { clock }
    private fun ok() = MockResponse().setBody(utilization)

    @Test fun `each size gets the largest layout that fits`() {
        assertEquals(StatsLayout.Strip, StatsLayout.forSize(DpSize(110.dp, 40.dp)))
        assertEquals(StatsLayout.Strip, StatsLayout.forSize(DpSize(60.dp, 30.dp)))
        assertEquals(StatsLayout.Bars, StatsLayout.forSize(DpSize(150.dp, 110.dp)))
        assertEquals(StatsLayout.Stacked, StatsLayout.forSize(DpSize(200.dp, 400.dp)))
        assertEquals(StatsLayout.Stacked, StatsLayout.forSize(DpSize(160.dp, 190.dp)))
        assertEquals(StatsLayout.WideBars, StatsLayout.forSize(DpSize(320.dp, 120.dp)))
        assertEquals(StatsLayout.Detailed, StatsLayout.forSize(DpSize(230.dp, 200.dp)))
    }

    @Test fun `a reading is fetched, shown and kept for the next run`() = runTest {
        server.enqueue(ok())
        store().refresh()

        val reloaded = store().state.value
        val snapshot = reloaded.snapshot!!
        assertEquals(42.0, snapshot.cpuPercent, 0.0)
        assertEquals(25.0, snapshot.memPercent, 0.1)
        assertEquals(25.0, snapshot.diskPercent!!, 0.1)
        assertNull(reloaded.error)
        assertFalse(reloaded.refreshing)
    }

    @Test fun `a failed refresh keeps the last reading and says why`() = runTest {
        val store = store()
        server.enqueue(ok())
        store.refresh()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
        store.refresh()

        assertEquals(42.0, store.state.value.snapshot!!.cpuPercent, 0.0)
        assertNotNull(store.state.value.error)
        assertNotNull("the error survives a restart", store().state.value.error)
    }

    @Test fun `a recent attempt is not repeated`() = runTest {
        val store = store()
        server.enqueue(ok())
        store.refresh(maxAgeMs = 60_000)
        clock += 30_000
        store.refresh(maxAgeMs = 60_000)
        assertEquals(1, server.requestCount)

        server.enqueue(ok())
        clock += 31_000
        store.refresh(maxAgeMs = 60_000)
        assertEquals(2, server.requestCount)
    }

    @Test fun `nothing is fetched while signed out`() = runTest {
        creds.signOut()
        store().refresh()
        assertEquals(0, server.requestCount)
    }

    @Test fun `a reading that arrives after sign-out is dropped`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                creds.signOut()
                return ok()
            }
        }
        val store = store()
        store.refresh()
        assertNull(store.state.value.snapshot)
        assertNull(storage.saved)
    }

    @Test fun `another server's reading is not shown`() = runTest {
        server.enqueue(ok())
        store().refresh()
        creds.baseUrl = "http://other.local"
        assertNull(store().state.value.snapshot)
    }

    @Test fun `clearing forgets the reading`() = runTest {
        val store = store()
        server.enqueue(ok())
        store.refresh()
        store.clear()
        assertNull(store.state.value.snapshot)
        assertNull(store().state.value.snapshot)
    }
}
