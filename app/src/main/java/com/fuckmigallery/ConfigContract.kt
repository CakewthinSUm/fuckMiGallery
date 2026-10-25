package com.fuckmigallery

/**
 * Settings keys shared by the UI and the hook.
 *
 * The values themselves travel through a file (see [ConfigStore] / [HookConfig]) —
 * a ContentProvider was tried and does not work: the camera process cannot resolve
 * this app's authority under Android 11+ package visibility.
 */
object ConfigContract {
    /** Package name of this module app. */
    const val MODULE_PACKAGE = "com.fuckmigallery"

    /** SharedPreferences file backing the settings screen. */
    const val PREFS = "fuckmigallery_prefs"
    const val PREF_ENABLED = "enabled"
    const val PREF_TARGET_PACKAGE = "target_package"
    const val PREF_VERBOSE_LOG = "verbose_log"

    /** Sentinel for "no explicit choice — follow whatever the system resolves". */
    const val TARGET_FOLLOW_SYSTEM = ""
}
