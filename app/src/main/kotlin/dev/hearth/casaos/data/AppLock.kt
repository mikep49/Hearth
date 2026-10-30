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

class PrefsLockStorage(context: Context) : LockStorage {
    private val prefs = context.getSharedPreferences("lock", Context.MODE_PRIVATE)
    override var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()
    override var lockAfter: String?
        get() = prefs.getString("lock_after", null)
        set(value) = prefs.edit().putString("lock_after", value).apply()
}

/**
 * The optional lock in front of Hearth. When it's on, Hearth starts locked and locks again after being in
 * the background for [lockAfter]. Unlocking uses the phone's own screen lock (fingerprint, face, PIN,
 * pattern or password), so Hearth never stores a PIN itself.
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
        fun create(context: Context): AppLock {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            return AppLock(PrefsLockStorage(context), canLock = { keyguard.isDeviceSecure }, clock = SystemClock::elapsedRealtime)
        }
    }
}
