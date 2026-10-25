package com.fuckmigallery

import android.app.Application
import com.topjohnwu.superuser.Shell

/**
 * Requests root once at startup.
 *
 * Root is not optional for this module: it is needed to publish the settings to a
 * path the camera process can read (see [ConfigStore]). Asking here means the grant
 * prompt appears when the app is opened, not in the middle of saving a setting.
 */
class ModuleApp : Application() {

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10),
        )
    }

    override fun onCreate() {
        super.onCreate()
        Shell.getShell { shell ->
            android.util.Log.i(
                "FuckMiGallery",
                "root shell available=${shell.isRoot}",
            )
        }
    }
}
