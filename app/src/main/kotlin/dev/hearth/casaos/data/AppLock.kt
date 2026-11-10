package dev.hearth.casaos.data

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long Hearth can be in the background before it locks again. */
enum class LockAfter(val label: String, val millis: Long) {
    Immediately("Right away", 0),
    OneMinute("1 minute", 60_000),
    FiveMinutes("5 minutes", 5 * 60_000),
}

/** Where the lock settings are kept. An interface so [AppLock] can be tested without Android. */
interface LockStorage {
    var enabled: Boolean
    var lockAfter: String?
}

/** [part] names the part of Hearth a lock is for ("terminal"); null is the lock in front of all of Hearth. */
class PrefsLockStorage(context: Context, part: String? = null) : LockStorage {
    private val prefs = context.getSharedPreferences("lock", Context.MODE_PRIVATE)
    private val prefix = part?.let { "${it}_" }.orEmpty()
    override var enabled: Boolean
        get() = prefs.getBoolean("${prefix}enabled", false)
        set(value) = prefs.edit().putBoolean("${prefix}enabled", value).apply()
    override var lockAfter: String?
        get() = prefs.getString("${prefix}lock_after", null)
        set(value) = prefs.edit().putString("${prefix}lock_after", value).apply()
}

/**
 * An optional lock in front of Hearth, or in front of one part of it (the terminal, apps opened in Hearth).
 * When it's on, what it guards starts locked and locks again after Hearth has been in the background for
 * [lockAfter]. A part also locks when it's closed, with [lock]. Unlocking uses the phone's own screen lock
 * (fingerprint, face, PIN, pattern or password), so Hearth never stores a PIN itself.
 *
 * [canLock] says whether the phone has a screen lock. Without one there's nothing to unlock with, so Hearth
 * doesn't lock (removing the screen lock already takes the owner's PIN).
 */
class AppLock(
    private val storage: LockStorage,
    private val canLock: () -> Boolean,
    private val clock: () -> Long,
) {
    private val _enabled = MutableStateFlow(storage.enabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _lockAfter = MutableStateFlow(
        LockAfter.entries.firstOrNull { it.name == storage.lockAfter } ?: LockAfter.OneMinute,
    )
    val lockAfter: StateFlow<LockAfter> = _lockAfter.asStateFlow()

    private val _locked = MutableStateFlow(storage.enabled && canLock())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** When Hearth went to the background, if it was unlocked then. */
    private var leftAt: Long? = null

    fun available(): Boolean = canLock()

    fun onBackground() {
        if (!_locked.value) leftAt = clock()
    }

    fun onForeground() {
        if (_locked.value && !canLock()) {
            _locked.value = false
            return
        }
        val left = leftAt ?: return
        leftAt = null
        if (_enabled.value && canLock() && clock() - left >= _lockAfter.value.millis) _locked.value = true
    }

    /** Called after the phone confirmed it's the owner. */
    fun unlock() {
        leftAt = null
        _locked.value = false
    }

    /** Locks now, e.g. when the part this guards is closed. */
    fun lock() {
        leftAt = null
        if (_enabled.value && canLock()) _locked.value = true
    }

    /** Turning the lock on or off is only offered after the owner has confirmed, so it stays unlocked either way. */
    fun setEnabled(value: Boolean) {
        storage.enabled = value
        _enabled.value = value
        if (!value) _locked.value = false
    }

    fun setLockAfter(value: LockAfter) {
        storage.lockAfter = value.name
        _lockAfter.value = value
    }

    companion object {
        /** The lock for all of Hearth, or for one [part] of it. */
        fun create(context: Context, part: String? = null): AppLock {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            return AppLock(PrefsLockStorage(context, part), canLock = { keyguard.isDeviceSecure }, clock = SystemClock::elapsedRealtime)
        }
    }
}
