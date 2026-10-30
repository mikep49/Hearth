package dev.hearth.casaos

import dev.hearth.casaos.data.KeyValueStore
import dev.hearth.casaos.data.Secrets
import dev.hearth.casaos.data.SessionStore
import dev.hearth.casaos.data.TokenDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryPrefs(val values: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
    override fun get(key: String): String? = values[key]
    override fun edit(changes: Map<String, String?>) {
        changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
    }
}

/** Stand-in for the Keystore: reversible, and visibly not the plain token. */
private object Rot : Secrets {
    override fun encrypt(plain: String) = "enc:" + plain.reversed()
    override fun decrypt(blob: String) = blob.removePrefix("enc:").reversed().takeIf { blob.startsWith("enc:") }
}

class SessionStoreTest {
    private val prefs = MemoryPrefs()
    private fun store() = SessionStore(prefs, Rot)
    private fun token(n: Int) = TokenDto(accessToken = "A$n", refreshToken = "R$n", expiresAt = 0)

    @Test fun `signing in to a second server keeps the first, and shows the new one`() {
        val store = store()
        store.signIn("http://nas.local", "klaus", token(1))
        val nas = store.session.value!!
        store.signIn("http://pi.local", "anna", token(2))

        assertEquals(listOf("http://nas.local", "http://pi.local"), store.profiles.value.map { it.baseUrl })
        assertEquals("http://pi.local", store.session.value!!.baseUrl)
        assertEquals("A2", store.accessToken)

        store.switchTo(nas.id)
        assertEquals(nas, store.session.value)
        assertEquals("A1", store.accessToken)
        assertEquals("R1", store.refreshToken)
        assertEquals("the choice survives a restart", nas, store().session.value)
        assertTrue("tokens are stored encrypted", prefs.values.values.none { it == "A1" || it == "R1" })
    }

    @Test fun `signing in again to a saved server updates it rather than adding another`() {
        val store = store()
        store.signIn("http://nas.local", "klaus", token(1))
        val id = store.session.value!!.id
        store.signOut()
        assertNull(store.session.value)
        assertEquals(id, store.lastProfile?.id)

        store.signIn("http://nas.local", "Klaus", token(3))
        assertEquals(1, store.profiles.value.size)
        assertEquals(id, store.session.value!!.id)
        assertEquals("A3", store.accessToken)
    }

    @Test fun `each server's login works on its own, whichever is shown`() {
        val store = store()
        store.signIn("http://nas.local", "klaus", token(1))
        val nas = store.session.value!!.id
        store.signIn("http://pi.local", "anna", token(2))

        val creds = store.credentialsFor(nas)
        assertEquals("http://nas.local", creds.baseUrl)
        assertEquals("A1", creds.accessToken)
        creds.updateTokens(token(5))
        assertEquals("A5", store().credentialsFor(nas).accessToken)
        assertEquals("the shown server is untouched", "A2", store.accessToken)

        // The nas login ends (e.g. its refresh failed in a widget): the shown server stays signed in.
        creds.signOut()
        assertNull(creds.baseUrl)
        assertFalse(store.profiles.value.first { it.id == nas }.signedIn)
        assertEquals("http://pi.local", store.session.value!!.baseUrl)
        store.switchTo(nas)
        assertEquals("a signed-out server can't be switched to", "http://pi.local", store.session.value!!.baseUrl)
    }

    @Test fun `removing a server forgets it and its tokens`() {
        val store = store()
        store.signIn("http://nas.local", "klaus", token(1))
        val id = store.session.value!!.id
        store.remove(id)
        assertNull(store.session.value)
        assertNull(store.lastProfile)
        assertTrue(store.profiles.value.isEmpty())
        assertTrue(prefs.values.keys.none { id in it })
    }

    @Test fun `a token refresh that lands after sign-out doesn't sign back in`() {
        val store = store()
        store.signIn("http://nas.local", "klaus", token(1))
        val creds = store.credentialsFor(store.session.value!!.id)
        store.signOut()
        creds.updateTokens(token(2))
        assertNull(creds.accessToken)
        assertNull(store().session.value)
    }

    @Test fun `the login from before servers could be saved carries over`() {
        prefs.values += mapOf(
            "base_url" to "http://nas.local",
            "username" to "klaus",
            "access_token" to Rot.encrypt("A1"),
            "refresh_token" to Rot.encrypt("R1"),
            "last_server" to "http://nas.local",
        )
        val store = store()
        assertEquals("http://nas.local", store.session.value!!.baseUrl)
        assertEquals("klaus", store.session.value!!.username)
        assertEquals("A1", store.accessToken)
        assertEquals("R1", store.refreshToken)
        assertTrue(listOf("base_url", "username", "access_token", "refresh_token", "last_server").none { it in prefs.values })
    }
}
