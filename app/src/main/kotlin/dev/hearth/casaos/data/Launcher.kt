package dev.hearth.casaos.data

import android.app.Activity
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import dev.hearth.casaos.HomeActivity
import dev.hearth.casaos.MainActivity

/**
 * Hearth as the home screen, for a tablet kept just for the server: Android opens it at startup and when Home
 * is pressed, in place of the usual launcher.
 *
 * [HomeActivity] is what makes Hearth a home app. It's disabled until [setOffered] enables it, so Android
 * doesn't list Hearth among the home apps before then. Disabling it again makes Android go back to the home
 * app it had.
 */
class Launcher(private val context: Context) {
    private val roles: RoleManager? = context.getSystemService(RoleManager::class.java)
    private val home = ComponentName(context, HomeActivity::class.java)

    /** Whether this device has a home app to replace at all. */
    val available: Boolean get() = roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true

    /** Set while Android's question, or its page for choosing the home app, is open: Hearth has to stay on offer until it's answered. */
    var choosing = false

    /** Whether Hearth is the home app right now. It can change at any time in Android's settings. */
    fun isHome(): Boolean = roles?.isRoleHeld(RoleManager.ROLE_HOME) == true

    /**
     * Puts Hearth among the home apps Android can use, or takes it out, which also stops it being the home app.
     * Returns whether that changed anything.
     */
    fun setOffered(offered: Boolean): Boolean {
        val pm = context.packageManager
        val isOn = pm.getComponentEnabledSetting(home) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if (isOn == offered) return false
        val state = if (offered) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        pm.setComponentEnabledSetting(home, state, PackageManager.DONT_KILL_APP)
        return true
    }

    /** Android's own question, whether to make Hearth the home app. Needs [setOffered] first. */
    fun askIntent(): Intent? = roles?.createRequestRoleIntent(RoleManager.ROLE_HOME)

    /** Android's page for choosing the home app. */
    fun chooseIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS)

    /**
     * Keeps [activity] in step with Android as it comes to the front, and returns [isHome].
     *
     * While Hearth is the home app there's one Hearth, the home screen: one opened beside it, such as from a
     * notification, shows the home screen and closes. And once Hearth isn't the home app, its home screen
     * closes, which Android doesn't do by itself. What shows then is the home app Android has now.
     */
    fun settle(activity: Activity): Boolean {
        val isHome = isHome()
        when {
            activity.isFinishing -> {}
            activity is HomeActivity && isHome -> {
                // Android shows the home screen the moment Hearth becomes it, before the Hearth that asked
                // hears the answer, which would leave that one in Recents.
                if (choosing) close(MainActivity::class.java)
                choosing = false
            }
            // Still being decided, so Hearth stays on offer.
            choosing -> {}
            activity is HomeActivity -> leave(activity)
            isHome -> {
                runCatching { activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                activity.finishAndRemoveTask()
            }
            // Another home app was picked in Android's settings. Hearth stops offering itself too, so the
            // switch in Settings is all there is to it, and the home screen left in the background goes.
            else -> if (setOffered(false)) close(HomeActivity::class.java)
        }
        return isHome
    }

    /** Stops Hearth being the home app, from its home screen: Android goes back to the home app it had before. */
    fun leave(activity: Activity) {
        setOffered(false)
        if (activity is HomeActivity) activity.finishAndRemoveTask()
    }

    /** Closes Hearth's tasks that were opened as [activity]. Apps opened from their own home screen icons are other activities. */
    private fun close(activity: Class<out Activity>) {
        val tasks = context.getSystemService(ActivityManager::class.java)?.appTasks ?: return
        for (task in tasks) runCatching {
            if (task.taskInfo?.baseIntent?.component?.className == activity.name) task.finishAndRemoveTask()
        }
    }

    /**
     * Android's settings. With Hearth as the home screen there's no app list to open them from, and they're
     * also where the other apps and the choice of home app are.
     */
    fun openAndroidSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
