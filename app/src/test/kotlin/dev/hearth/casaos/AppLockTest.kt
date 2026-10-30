package dev.hearth.casaos

import dev.hearth.casaos.data.AppLock
import dev.hearth.casaos.data.LockAfter
import dev.hearth.casaos.data.LockStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryLockStorage(override var enabled: Boolean = false, override var lockAfter: String? = null) : LockStorage

class AppLockTest {
    private var now = 1_000L
    private var secure = true

    private fun lock(storage: LockStorage) = AppLock(storage, canLock = { secure }, clock = { now })

    @Test fun startsLockedWhenOn() {
        assertTrue(lock(MemoryLockStorage(enabled = true)).locked.value)
        assertFalse(lock(MemoryLockStorage(enabled = false)).locked.value)
    }

    @Test fun doesNotLockWithoutAScreenLock() {
        secure = false
        assertFalse(lock(MemoryLockStorage(enabled = true)).locked.value)
    }

    @Test fun defaultsToOneMinute() {
        assertEquals(LockAfter.OneMinute, lock(MemoryLockStorage()).lockAfter.value)
    }

    @Test fun locksAgainOnlyAfterTheChosenTime() {
        val lock = lock(MemoryLockStorage(enabled = true, lockAfter = LockAfter.OneMinute.name))
        lock.unlock()

        lock.onBackground(); now += 30_000; lock.onForeground()
        assertFalse(lock.locked.value)

        lock.onBackground(); now += 60_000; lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test fun rightAwayLocksOnEveryReturn() {
        val lock = lock(MemoryLockStorage(enabled = true, lockAfter = LockAfter.Immediately.name))
        lock.unlock()
        lock.onBackground(); lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test fun unlockingWhileAwayDoesNotRelockOnReturn() {
        // The PIN screen can briefly send Hearth to the background; its success may land before Hearth returns.
        val lock = lock(MemoryLockStorage(enabled = true, lockAfter = LockAfter.Immediately.name))
        lock.unlock()
        lock.onBackground()
        lock.unlock()
        lock.onForeground()
        assertFalse(lock.locked.value)
    }

    @Test fun offNeverLocks() {
        val storage = MemoryLockStorage(enabled = true, lockAfter = LockAfter.Immediately.name)
        val lock = lock(storage)
        lock.setEnabled(false)
        assertFalse(lock.locked.value)
        assertFalse(storage.enabled)
        lock.onBackground(); now += 600_000; lock.onForeground()
        assertFalse(lock.locked.value)
    }

    @Test fun turningOnKeepsItUnlockedUntilLeaving() {
        val storage = MemoryLockStorage()
        val lock = lock(storage)
        lock.setEnabled(true)
        assertTrue(storage.enabled)
        assertFalse(lock.locked.value)
    }

    @Test fun removingTheScreenLockUnlocks() {
        val lock = lock(MemoryLockStorage(enabled = true))
        assertTrue(lock.locked.value)
        secure = false
        lock.onForeground()
        assertFalse(lock.locked.value)
    }

    @Test fun remembersTheChoice() {
        val storage = MemoryLockStorage()
        lock(storage).setLockAfter(LockAfter.FiveMinutes)
        assertEquals(LockAfter.FiveMinutes, lock(storage).lockAfter.value)
    }
}
