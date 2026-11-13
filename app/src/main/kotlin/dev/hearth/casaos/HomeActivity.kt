package dev.hearth.casaos

/**
 * Hearth as the tablet's home screen: the same app, started by Android at startup and when Home is pressed.
 *
 * It's an activity of its own, with its own task, rather than another name for [MainActivity]. Android can
 * then tell the two apart, and never puts a Hearth opened from an icon or a notification into the home
 * screen's task, where Recents wouldn't show it. See [dev.hearth.casaos.data.Launcher].
 */
class HomeActivity : MainActivity()
