package com.fuckmigallery

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Decides whether an intent is the camera's "open the shot I just took" call, and
 * rewrites it to point at the user's gallery instead of MIUI Gallery.
 *
 * Deliberately narrow: it only touches intents that carry one of the camera's REVIEW
 * actions *and* are addressed to MIUI Gallery. Everything else passes through
 * untouched, so the rest of the camera's intent traffic is unaffected.
 */
object IntentInterceptor {

    /** Guards against re-intercepting our own re-dispatch. */
    private val inFlight = ThreadLocal<Boolean>()

    /**
     * Rewrites [intent] in place when it matches.
     *
     * Mutating the caller's object (rather than replacing the argument) is what makes
     * this work uniformly across the several framework entry points we hook: they all
     * pass the same Intent instance on to the next layer.
     *
     * @return true when the intent was rewritten.
     */
    fun intercept(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        if (inFlight.get() == true) return false

        if (!isCameraReviewIntent(intent)) return false

        val config = HookConfig.read(context)
        if (config == null) {
            ModuleLog.w("no config available; leaving REVIEW intent alone")
            return false
        }
        ModuleLog.d("config: enabled=${config.enabled} target='${config.targetPackage}'")
        if (!config.enabled) {
            ModuleLog.d("disabled in settings; leaving REVIEW intent alone")
            return false
        }

        val target = GalleryResolver.resolveTarget(context, config.targetPackage)
        if (target == null) {
            ModuleLog.d("no target gallery; leaving REVIEW intent alone")
            return false
        }
        if (target == MiuiPackages.MIUI_GALLERY) return false

        return try {
            inFlight.set(true)
            rewrite(context, intent, target)
        } catch (t: Throwable) {
            // Anything at all going wrong here must not break the camera.
            ModuleLog.e("rewrite failed; keeping original intent", t)
            false
        } finally {
            inFlight.set(false)
        }
    }

    /**
     * Matches: one of the camera's REVIEW actions, addressed to MIUI Gallery.
     *
     * Note the action check is what keeps this from touching the camera's other
     * gallery traffic (e.g. the `BIND_SERVICE` calls it makes to MIUI Gallery).
     */
    private fun isCameraReviewIntent(intent: Intent): Boolean {
        val action = intent.action ?: return false
        if (action !in MiuiPackages.REVIEW_ACTIONS) return false

        val addressedToMiuiGallery =
            intent.`package` == MiuiPackages.MIUI_GALLERY ||
                intent.component?.packageName == MiuiPackages.MIUI_GALLERY
        if (!addressedToMiuiGallery) return false

        if (intent.data == null) {
            ModuleLog.d("REVIEW intent without data; skipping")
            return false
        }
        return true
    }

    /** Retargets the intent, degrading the action if the target cannot handle it. */
    private fun rewrite(context: Context, intent: Intent, target: String): Boolean {
        val originalAction = intent.action
        val data: Uri? = intent.data

        // The camera's REVIEW action is private to Xiaomi. Most galleries only
        // declare ACTION_VIEW, so fall back to it before giving up.
        val candidates = buildList {
            add(originalAction!!)
            add(Intent.ACTION_VIEW)
        }

        for (action in candidates) {
            val probe = Intent(intent).apply {
                this.action = action
                setPackage(target)
                setComponent(null)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (!GalleryResolver.canDeliver(context, probe, target)) {
                ModuleLog.d("$target cannot handle $action for $data")
                continue
            }

            // Commit the mutation onto the caller's intent.
            intent.action = action
            intent.setPackage(target)
            intent.setComponent(null)
            // Without this the target gallery may be unable to read the MediaStore
            // URI the camera handed over.
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // MIUI-specific read hint; meaningless to third-party galleries but
            // harmless, and dropping it would change behaviour if we ever fall back.
            intent.removeExtra(MiuiPackages.EXTRA_FROM_CAMERA)

            ModuleLog.i("redirected REVIEW ($originalAction -> $action) to $target for $data")
            return true
        }

        ModuleLog.w("no deliverable target for $data; keeping MIUI Gallery")
        return false
    }
}
