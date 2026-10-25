package com.fuckmigallery

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * Logging for the hooked side.
 *
 * Verbose logging is opt-in and defaults to off, so a normal install stays quiet in
 * logcat. Errors always go out — a silent failure here looks exactly like "the module
 * does nothing", which is the hardest kind of bug to chase on a phone.
 */
object ModuleLog {
    private const val TAG = "FuckMiGallery"

    @Volatile
    private var verbose = false

    fun setVerbose(enabled: Boolean) {
        verbose = enabled
    }

    fun d(message: String) {
        if (!verbose) return
        Log.d(TAG, message)
        runCatching { XposedBridge.log("[$TAG] $message") }
    }

    fun i(message: String) {
        Log.i(TAG, message)
        runCatching { XposedBridge.log("[$TAG] $message") }
    }

    fun w(message: String, t: Throwable? = null) {
        Log.w(TAG, message, t)
        runCatching {
            XposedBridge.log("[$TAG][W] $message")
            if (t != null) XposedBridge.log(t)
        }
    }

    fun e(message: String, t: Throwable? = null) {
        Log.e(TAG, message, t)
        runCatching {
            XposedBridge.log("[$TAG][E] $message")
            if (t != null) XposedBridge.log(t)
        }
    }
}
