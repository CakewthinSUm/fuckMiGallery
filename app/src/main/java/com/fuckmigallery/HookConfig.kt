package com.fuckmigallery

import android.content.Context
import java.io.File

/**
 * Reads the module's settings from inside the hooked camera process.
 *
 * The channel is a plain file at [CONFIG_PATH], written by the settings app through a
 * root shell. That indirection exists because neither of the obvious approaches works
 * here, both verified on this device:
 *
 *  - The module's own data dir is tagged `app_data_file` with the app's SELinux
 *    categories. The camera process is a different app domain and gets EACCES no
 *    matter what the Unix mode says.
 *  - `XSharedPreferences` (with the `xposedsharedprefs` manifest flag) read stale
 *    values and, worse, silently swallowed the settings app's own writes.
 *
 * `/data/local/tmp` is `shell_data_file`, which any app domain can read.
 */
object HookConfig {
    private const val CACHE_TTL_MS = 1_000L

    /** Must match [ConfigStore.PUBLISHED_PATH]. */
    const val CONFIG_PATH = "/data/local/tmp/fuckmigallery_config"

    @Volatile
    private var cached: Snapshot? = null

    @Volatile
    private var cachedAt = 0L

    data class Snapshot(
        val enabled: Boolean,
        val targetPackage: String,
        val verboseLog: Boolean,
    )

    /** Returns null when the config cannot be read — callers must fail open. */
    fun read(@Suppress("UNUSED_PARAMETER") context: Context): Snapshot? {
        val now = System.currentTimeMillis()
        val hit = cached
        if (hit != null && now - cachedAt <= CACHE_TTL_MS) return hit

        val fresh = readFromFile()
        return if (fresh != null) {
            cached = fresh
            cachedAt = now
            ModuleLog.setVerbose(fresh.verboseLog)
            fresh
        } else {
            ModuleLog.d("no config at $CONFIG_PATH; using stale value")
            hit
        }
    }

    /**
     * Format: three lines — `enabled`, `target_package`, `verbose_log`.
     * Kept trivial so it cannot fail on a malformed edge case.
     */
    private fun readFromFile(): Snapshot? = try {
        val f = File(CONFIG_PATH)
        if (!f.canRead()) {
            ModuleLog.d("not readable: $CONFIG_PATH")
            null
        } else {
            val lines = f.readLines()
            if (lines.size < 3) {
                ModuleLog.w("malformed config ($CONFIG_PATH, ${lines.size} lines)")
                null
            } else {
                Snapshot(
                    enabled = lines[0].trim() == "1",
                    targetPackage = lines[1].trim(),
                    verboseLog = lines[2].trim() == "1",
                ).also { ModuleLog.i("config: $it") }
            }
        }
    } catch (t: Throwable) {
        ModuleLog.w("failed to read $CONFIG_PATH", t)
        null
    }
}
