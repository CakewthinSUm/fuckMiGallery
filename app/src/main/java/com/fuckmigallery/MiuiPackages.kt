package com.fuckmigallery

/**
 * Package and action names specific to HyperOS / MIUI.
 *
 * Everything here is a *stable* identifier published by the camera or declared in a
 * manifest — not an obfuscated class or method name. That is deliberate: obfuscated
 * names change with every HyperOS release, these do not.
 */
object MiuiPackages {
    /** The camera app we hook. */
    const val CAMERA = "com.android.camera"

    /** The gallery the camera hard-codes into its REVIEW intent. */
    const val MIUI_GALLERY = "com.miui.gallery"

    /**
     * Private action the camera uses for the thumbnail tap.
     *
     * Verified on MiuiCamera 6.7.000180.0:
     * `START u0 {act=com.android.camera.action.REVIEW ... pkg=com.miui.gallery ...}`
     */
    const val ACTION_REVIEW = "com.android.camera.action.REVIEW"

    /**
     * Multi-window variant. The missing "L" in "SPILIT" is Xiaomi's own typo and is
     * part of the wire format — do not "fix" it.
     */
    const val ACTION_SPLIT_REVIEW = "com.android.camera.action.SPILIT_SCREEN_REVIEW"

    /** Extra the camera attaches; MIUI Gallery uses it to pick a privileged read path. */
    const val EXTRA_FROM_CAMERA = "from_MiuiCamera"

    val REVIEW_ACTIONS = setOf(ACTION_REVIEW, ACTION_SPLIT_REVIEW)

    /** Xiaomi's own photo viewer; also declares REVIEW and would defeat the point. */
    const val MIUI_MEDIA_VIEWER = "com.miui.mediaviewer"

    /**
     * Xiaomi apps that must never be chosen as the redirect target.
     *
     * `mediaviewer` matters most: it declares the camera's REVIEW action, so a
     * "first gallery that declares REVIEW" heuristic picks it and the photo ends up
     * in a Xiaomi app again.
     */
    val XIAOMI_APPS = setOf(
        MIUI_GALLERY,
        MIUI_MEDIA_VIEWER,
    )

    fun isXiaomi(pkg: String): Boolean =
        pkg in XIAOMI_APPS || pkg.startsWith("com.miui.") || pkg.startsWith("com.xiaomi.")
}
