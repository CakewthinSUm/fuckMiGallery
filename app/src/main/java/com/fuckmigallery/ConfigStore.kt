package com.fuckmigallery

import android.content.Context
import java.io.File

/**
 * The module's settings, and the one place that can publish them where the hook can
 * read them.
 *
 * The cross-process problem here has no permission-only solution:
 *
 *  - A file in this app's data dir is tagged `app_data_file` with the app's SELinux
 *    categories; the camera process (a different app domain) cannot read it, whatever
 *    the Unix mode says. Verified: EACCES.
 *  - `/data/local/tmp` is readable from any app domain, but an ordinary app cannot
 *    write there. Verified: EACCES on create.
 *  - The framework's `xposedsharedprefs` flag did not produce a readable copy on this
 *    framework build, and while declared it silently swallowed this app's own prefs
 *    writes.
 *
 * This module already requires root + an Xposed framework, so it publishes through a
 * root shell: write to `/data/local/tmp/fuckmigallery_config`, which the hook reads
 * directly. If root is unavailable the settings screen says so rather than pretending
 * to work.
 */
object ConfigStore {

    /** Where the hook looks. Must match [HookConfig.CONFIG_PATH]. */
    const val PUBLISHED_PATH = HookConfig.CONFIG_PATH

    const val FILE_NAME = "module_config"

    /** Local copy, so the UI works (and survives a restart) even without root. */
    private fun localFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun read(context: Context): Triple<Boolean, String, Boolean> {
        val file = localFile(context)
        if (!file.exists()) return Triple(false, ConfigContract.TARGET_FOLLOW_SYSTEM, false)
        return runCatching { decode(file.readText()) }
            .getOrElse { Triple(false, ConfigContract.TARGET_FOLLOW_SYSTEM, false) }
    }

    /**
     * Persists locally, then publishes to [PUBLISHED_PATH] via root.
     *
     * @return true when the settings reached the path the hook reads.
     */
    fun write(context: Context, enabled: Boolean, targetPackage: String, verboseLog: Boolean): Boolean {
        val payload = encode(enabled, targetPackage, verboseLog)

        runCatching {
            localFile(context).apply {
                parentFile?.mkdirs()
                writeText(payload)
            }
        }

        return publish(payload)
    }

    /** Copies the payload into place as root. */
    private fun publish(payload: String): Boolean = try {
        // Go through the root shell rather than SuFile: wrapping a SuFile in a plain
        // FileOutputStream still opens it with the app's own credentials (EACCES).
        // The payload is a package name and two flags, but quote it anyway so a
        // hostile value cannot break out of the heredoc.
        val quoted = payload.replace("'", "'\\''")
        val result = com.topjohnwu.superuser.Shell.cmd(
            "printf '%s' '$quoted' > $PUBLISHED_PATH",
            "chmod 644 $PUBLISHED_PATH",
        ).exec()
        if (!result.isSuccess) {
            android.util.Log.w("FuckMiGallery", "publish failed: ${result.err.joinToString()}")
        }
        result.isSuccess
    } catch (t: Throwable) {
        android.util.Log.w("FuckMiGallery", "publish failed", t)
        false
    }

    /** `enabled\ntarget_package\nverbose_log`, booleans as 1/0. */
    fun encode(enabled: Boolean, targetPackage: String, verboseLog: Boolean): String = buildString {
        append(if (enabled) "1" else "0").append('\n')
        append(targetPackage).append('\n')
        append(if (verboseLog) "1" else "0").append('\n')
    }

    fun decode(text: String): Triple<Boolean, String, Boolean> {
        val lines = text.lines()
        if (lines.size < 3) return Triple(false, ConfigContract.TARGET_FOLLOW_SYSTEM, false)
        return Triple(
            lines[0].trim() == "1",
            lines[1].trim(),
            lines[2].trim() == "1",
        )
    }
}
