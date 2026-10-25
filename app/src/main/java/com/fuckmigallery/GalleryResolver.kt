package com.fuckmigallery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri

/**
 * Works out which gallery the camera's REVIEW intent should be sent to.
 *
 * Runs inside the camera process, so it only ever queries the PackageManager — it
 * never needs to launch anything.
 */
object GalleryResolver {

    /** A stand-in for "some photo in the library", used to probe VIEW handlers. */
    private val PROBE_URI: Uri = Uri.parse("content://media/external/images/media/1")

    /**
     * Uses the wildcard image type rather than a concrete one such as jpeg.
     *
     * Google Photos declares a *partial* type ("image") instead of concrete MIME
     * types, so a query for jpeg does not match it. The wildcard finds every gallery
     * that accepts images at all.
     */
    private fun probeIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        // setDataAndType, not setType: setting the type alone clears the data URI,
        // which changes which activities match (galleries that only declare a
        // concrete type are dropped).
        setDataAndType(PROBE_URI, "image/*")
    }

    /**
     * Picks the package to redirect to.
     *
     * @param explicit the user's choice, or [ConfigContract.TARGET_FOLLOW_SYSTEM].
     * @return the package name, or null when nothing suitable exists (caller then
     *         leaves the original intent untouched).
     */
    fun resolveTarget(context: Context, explicit: String): String? {
        if (explicit.isNotBlank() && !MiuiPackages.isXiaomi(explicit)) {
            if (isInstalled(context, explicit)) return explicit
            ModuleLog.w("configured gallery $explicit is not installed; falling back")
        }

        resolveSystemDefault(context)?.let { return it }

        // "Follow system" resolves to nothing on HyperOS (no persistent default
        // gallery), so pick a sensible non-MIUI gallery rather than silently
        // falling back to the very app the user asked us to avoid.
        return firstAvailableGallery(context)
    }

    /**
     * Best-effort pick when there is no default: a real gallery that is not MIUI
     * Gallery.
     *
     * Preference order matters — plenty of apps accept an image VIEW without being a
     * gallery (installers, editors, chat apps), and picking the alphabetically first
     * of those would be surprising. Apps that also declare the camera's REVIEW action
     * are the strongest signal, since only a gallery bothers to.
     */
    private fun firstAvailableGallery(context: Context): String? {
        // Exclude every Xiaomi app, not just Gallery: MIUI Media Viewer also declares
        // REVIEW and would otherwise win the "declares REVIEW" check below.
        val candidates = listGalleries(context).filterNot { MiuiPackages.isXiaomi(it.packageName) }
        if (candidates.isEmpty()) {
            ModuleLog.w("no third-party gallery installed; leaving intent alone")
            return null
        }

        val preferred = candidates.firstOrNull { declaresReviewAction(context, it.packageName) }
        val chosen = preferred ?: candidates.first()
        ModuleLog.d(
            "follow-system fallback: ${chosen.packageName}" +
                if (preferred == null) " (no gallery declares REVIEW; took first)" else " (declares REVIEW)",
        )
        return chosen.packageName
    }

    /** True when [pkg] advertises the camera's private REVIEW action. */
    private fun declaresReviewAction(context: Context, pkg: String): Boolean = try {
        val probe = Intent(MiuiPackages.ACTION_REVIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(PROBE_URI, "image/*")
            setPackage(pkg)
        }
        context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY) != null
    } catch (t: Throwable) {
        false
    }

    /**
     * Follows whatever the system resolves for a plain image VIEW.
     *
     * HyperOS has no persistent "default gallery" — a bare resolve returns the
     * ResolverActivity. So this only produces a usable answer when some gallery has
     * actually claimed the intent; otherwise it correctly yields null.
     */
    private fun resolveSystemDefault(context: Context): String? {
        val pm = context.packageManager
        val resolved: ResolveInfo? = try {
            pm.resolveActivity(probeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        } catch (t: Throwable) {
            ModuleLog.w("resolveActivity failed", t)
            null
        }

        val pkg = resolved?.activityInfo?.packageName
        if (pkg != null &&
            !MiuiPackages.isXiaomi(pkg) &&
            pkg != "android" &&
            !pkg.startsWith("com.android.intentresolver")
        ) {
            ModuleLog.d("system resolves images to $pkg")
            return pkg
        }

        ModuleLog.d("no system default gallery (resolved=${pkg ?: "none"})")
        return null
    }

    /**
     * True when [intent] can actually be delivered to [pkg].
     *
     * The camera's action is a Xiaomi-private one, so most galleries do not declare
     * it. Callers use this to decide whether to keep the action or downgrade it.
     */
    fun canDeliver(context: Context, intent: Intent, pkg: String): Boolean {
        val probe = Intent(intent).apply {
            setPackage(pkg)
            setComponent(null)
        }
        return try {
            context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY) != null
        } catch (t: Throwable) {
            ModuleLog.w("resolveActivity for $pkg failed", t)
            false
        }
    }

    /**
     * Every installed app that can open an image — the UI's picker list.
     *
     * Needs QUERY_ALL_PACKAGES: galleries differ in what they declare (Google Photos
     * declares a partial "image" type, others no scheme at all), so a `<queries>`
     * filter returned an incomplete list that omitted the user's actual gallery.
     */
    fun listGalleries(context: Context): List<GalleryEntry> {
        val pm = context.packageManager
        val found: List<ResolveInfo> = try {
            pm.queryIntentActivities(probeIntent(), PackageManager.MATCH_ALL)
        } catch (t: Throwable) {
            ModuleLog.w("queryIntentActivities failed", t)
            emptyList()
        }
        ModuleLog.d("gallery scan found ${found.size}")

        return found
            .mapNotNull { info ->
                val ai = info.activityInfo ?: return@mapNotNull null
                if (ai.packageName == context.packageName) return@mapNotNull null
                GalleryEntry(
                    packageName = ai.packageName,
                    label = ai.loadLabel(pm)?.toString() ?: ai.packageName,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.packageName == MiuiPackages.MIUI_GALLERY }, { it.label }))
    }

    data class GalleryEntry(val packageName: String, val label: String)

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
