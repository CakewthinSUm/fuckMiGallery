package com.fuckmigallery

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Entry point (see assets/xposed_init).
 *
 * Hooks the *framework's* intent dispatch rather than the camera's own gallery
 * classes. The camera's classes are obfuscated and get renamed every HyperOS
 * release (`bn.C10510g`, `p187F1.C2412d3`, ...), while these framework signatures
 * are stable. It also means every entry point the camera has — including ones added
 * in a future update — is covered by the same hook.
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != MiuiPackages.CAMERA) return

        ModuleLog.i("loaded in ${lpparam.packageName}")

        val loader = lpparam.classLoader
        var installed = 0

        installed += hookContextImpl(loader)
        installed += hookActivity(loader)
        installed += hookContextWrapper(loader)
        installed += hookInstrumentation(loader)

        if (installed == 0) {
            ModuleLog.e("no dispatch hooks installed — module is inert on this ROM")
        } else {
            ModuleLog.i("installed $installed dispatch hook(s)")
        }
    }

    /**
     * The main path. The camera calls `Activity.startActivity`, which lands here.
     */
    private fun hookContextImpl(loader: ClassLoader): Int {
        var n = 0
        n += hookAll(loader, "android.app.ContextImpl", "startActivity",
            arrayOf(Intent::class.java)) { ctx, intent -> intercept(ctx, intent) }
        n += hookAll(loader, "android.app.ContextImpl", "startActivity",
            arrayOf(Intent::class.java, android.os.Bundle::class.java)) { ctx, intent -> intercept(ctx, intent) }
        return n
    }

    /**
     * Some camera paths call through the Activity subclass directly, and
     * `startActivityForResult` is used when the camera expects to resume afterwards.
     */
    private fun hookActivity(loader: ClassLoader): Int {
        var n = 0
        n += hookAll(loader, "android.app.Activity", "startActivity",
            arrayOf(Intent::class.java)) { ctx, intent -> intercept(ctx, intent) }
        n += hookAll(loader, "android.app.Activity", "startActivity",
            arrayOf(Intent::class.java, android.os.Bundle::class.java)) { ctx, intent -> intercept(ctx, intent) }
        n += hookAll(loader, "android.app.Activity", "startActivityForResult",
            arrayOf(Intent::class.java, Integer.TYPE)) { ctx, intent -> intercept(ctx, intent) }
        n += hookAll(loader, "android.app.Activity", "startActivityForResult",
            arrayOf(Intent::class.java, Integer.TYPE, android.os.Bundle::class.java)) { ctx, intent ->
            intercept(ctx, intent)
        }
        return n
    }

    private fun hookContextWrapper(loader: ClassLoader): Int {
        var n = 0
        n += hookAll(loader, "android.content.ContextWrapper", "startActivity",
            arrayOf(Intent::class.java)) { ctx, intent -> intercept(ctx, intent) }
        n += hookAll(loader, "android.content.ContextWrapper", "startActivity",
            arrayOf(Intent::class.java, android.os.Bundle::class.java)) { ctx, intent -> intercept(ctx, intent) }
        return n
    }

    /**
     * Secondary path: some system components bypass the Context methods entirely.
     * Covered for completeness — the camera is not known to use it today.
     */
    private fun hookInstrumentation(loader: ClassLoader): Int =
        hookAll(
            loader,
            "android.app.Instrumentation",
            "execStartActivity",
            arrayOf(
                Context::class.java,
                android.os.IBinder::class.java,
                android.os.IBinder::class.java,
                Activity::class.java,
                Intent::class.java,
                Integer.TYPE,
                android.os.Bundle::class.java,
            ),
        ) { _, intent -> intercept(null, intent) }

    /**
     * Installs a before-hook on every overload matching [paramTypes], skipping any
     * that this ROM does not have. A missing overload is expected across Android
     * versions and must not abort the rest.
     */
    private fun hookAll(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramTypes: Array<Class<*>>,
        // (context, intent) -> Unit; context is null when the hook has no Context.
        onIntent: (Context?, Intent?) -> Unit,
    ): Int = try {
        XposedHelpers.findAndHookMethod(className, loader, methodName, *paramTypes, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                    val ctx = when (val self = param.thisObject) {
                        is Context -> self
                        is Activity -> self
                        else -> param.args.firstOrNull { it is Context } as? Context
                    }
                    onIntent(ctx, intent)
                } catch (t: Throwable) {
                    ModuleLog.e("hook body failed for $className.$methodName", t)
                }
            }
        })
        1
    } catch (t: Throwable) {
        // NoSuchMethodError is normal on ROMs that dropped an overload.
        ModuleLog.d("hook unavailable: $className.$methodName(${paramTypes.size} args) — ${t.javaClass.simpleName}")
        0
    }

    private fun intercept(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        IntentInterceptor.intercept(context, intent)
    }
}
