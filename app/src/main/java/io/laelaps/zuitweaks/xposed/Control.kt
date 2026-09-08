package io.laelaps.zuitweaks.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import io.laelaps.zuitweaks.settings.Channels
import java.lang.ref.WeakReference

/**
 * An adb-reachable poke hole into the external taskbar, for experimentation.
 *
 * Launcher3 exposes the stash machinery through unobfuscated public methods on
 * TaskbarActivityContext (toggleTaskbarStash, unstashTaskbarIfStashed,
 * setTaskbarWindowSize, onSwipeToUnstashTaskbar, ...). Rather than guessing which one
 * produces the behaviour we want and rebuilding between each guess, this invokes any of
 * them by name on the live external-display instance:
 *
 *   adb shell am broadcast -a io.laelaps.zuitweaks.CMD --es m toggleTaskbarStash
 *   adb shell am broadcast -a io.laelaps.zuitweaks.CMD --es m setTaskbarWindowSize --ei i 20
 *   adb shell am broadcast -a io.laelaps.zuitweaks.CMD --es m onSwipeToUnstashTaskbar --ez b true
 *   adb shell am broadcast -a io.laelaps.zuitweaks.CMD --es m dump
 *
 * Everything runs on the main looper - the taskbar controllers are not thread safe.
 */
object Control {

    const val ACTION = io.laelaps.zuitweaks.settings.Channels.CONTROL_ACTION

    private var dpContext = WeakReference<Any>(null)
    private var appContext = WeakReference<Context>(null)
    var autoHide: AutoHideController? = null
    private val main = Handler(Looper.getMainLooper())

    private var wanted = false
    private var registered = false

    /**
     * Register as soon as the launcher process has ANY context, rather than waiting for the
     * external taskbar. The drawer hooks call this: folder export/import has to work on a
     * tablet with no monitor attached, and until this existed the receiver only came up once
     * a Dp taskbar had been seen, so the settings app's request went to nobody.
     */
    fun attachContext(ctx: Context) {
        if (appContext.get() == null) appContext = WeakReference(ctx.applicationContext)
        if (wanted && !registered) register()
    }

    /** Called from the predicate overrides, which already know the receiver is the Dp one. */
    fun rememberDpContext(ctx: Any) {
        if (dpContext.get() !== ctx) {
            dpContext = WeakReference(ctx)
            Logx.i("captured external taskbar context: ${ctx.javaClass.name}")
        }
        // handleLoadPackage runs before the Application exists, so registration waits
        // until we have a real Context - which is exactly when the taskbar shows up.
        if (wanted && !registered) register()
    }

    fun requestRegistration() {
        wanted = true
    }

    /**
     * Register at process start instead of waiting for something to hand us a context.
     *
     * handleLoadPackage runs before the Application exists, so registration used to be deferred
     * until either the external taskbar appeared or the drawer first rendered. Both are events
     * that may never happen: with no monitor attached and the app drawer unopened since boot,
     * the receiver simply was not there, and the settings app's folder export got no answer and
     * silently wrote a file with no folders in it. Application.onCreate always runs, and it is
     * the first point in the process where a Context exists.
     */
    fun installEarlyRegistration(classLoader: ClassLoader) {
        Logx.guard("control early registration") {
            XposedHelpers.findAndHookMethod(
                "android.app.Application", classLoader, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("control attach") {
                            val ctx = param.thisObject as? Context ?: return@guard
                            attachContext(ctx)
                            // Second chance for the file log: this is the first point in the
                            // process where a Context exists, which is what the fallback path
                            // (an app's own external files dir) needs.
                            if (Flags.readConfig(ctx).bool("debugLog", false)) {
                                DebugLog.open(ctx, ctx.packageName.substringAfterLast('.'))
                            }
                        }
                    }
                },
            )
        }
    }

    fun register() {
        try {
            // AndroidAppHelper is absent from the trimmed api:82 jar, so go to the source.
            val app = (
                XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentApplication"
                ) as? Context
                ) ?: appContext.get()
                ?: dpContext.get() as? Context
                ?: run { Logx.e("no application context yet - control receiver deferred"); return }
            app.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        Logx.guard("control receiver") {
                            val i = intent ?: return@guard
                            // Answered here rather than in handle(): an ordered broadcast's
                            // result must be set before onReceive returns, and handle() posts to
                            // the main looper and refuses anything before an external taskbar has
                            // been captured. Folders touch only SQLite, which is thread safe.
                            if (handleFolders(context, i, this)) return@guard
                            handle(i)
                        }
                    }
                },
                IntentFilter(ACTION),
                Context.RECEIVER_EXPORTED,
            )
            registered = true
            Logx.i("control receiver registered on ${app.javaClass.simpleName}: am broadcast -a $ACTION --es m <method>")
        } catch (t: Throwable) {
            Logx.e("could not register control receiver", t)
        }
    }

    /**
     * Launcher methods that write persistent state. toggleTaskbarStash stores
     * `taskbar_is_stashed` in the launcher's SharedPreferences, which is app-wide - it
     * stashed the *internal* display's taskbar too, and survived both a kill switch and
     * a launcher restart. Learned the hard way; require an explicit opt-in.
     */
    private val PERSISTS_STATE = setOf("toggleTaskbarStash", "stash:toggleTaskbarStash")

    /**
     * The commands this receiver answers by default.
     *
     * The filter is RECEIVER_EXPORTED - it has to be, the settings app and the touchpad
     * process are both separate packages - so any app on the device can send this intent.
     * Everything below is a named, side-effect-bounded operation. The `stash:<name>` path
     * and the bare "call any method on the taskbar by name" path are reflection into the
     * launcher driven by an attacker-controlled string, so they are off unless
     * `controlReflect=1` is set in one of the debug override channels.
     */
    private val ALLOWED = setOf(
        "dump",
        "viewdump",
        "collapse",
        "expand",
        io.laelaps.zuitweaks.settings.Channels.CMD_RELOAD,
        "edgeEnter",
        "edgeExit",
    )

    /**
     * The drawer-folder commands. Returns true when [intent] was one of them, so the caller
     * knows not to fall through to the reflective path.
     *
     * Export replies through the ordered broadcast's result data. If the broadcast was not sent
     * ordered there is nowhere to put the answer, so that is logged rather than silently doing
     * nothing - a settings file quietly missing its folders is exactly the bug this fixes.
     */
    private fun handleFolders(context: Context?, intent: Intent, rx: BroadcastReceiver): Boolean {
        val method = intent.getStringExtra(Channels.EXTRA_METHOD) ?: return false
        val ctx = context ?: appContext.get()
        when (method) {
            Channels.CMD_FOLDERS_EXPORT -> {
                if (ctx != null) FolderStore.init(ctx)
                val text = FolderStore.exportText()
                if (rx.isOrderedBroadcast) {
                    rx.resultData = text
                    rx.resultCode = android.app.Activity.RESULT_OK
                } else {
                    Logx.e("control: ${Channels.CMD_FOLDERS_EXPORT} needs an ordered broadcast")
                }
                Logx.i("control: exported ${text.lineSequence().count { it.startsWith("folder.group=") }} folder(s)")
            }
            Channels.CMD_FOLDERS_IMPORT -> {
                if (ctx != null) FolderStore.init(ctx)
                val n = FolderStore.importText(intent.getStringExtra(Channels.EXTRA_DATA).orEmpty())
                if (rx.isOrderedBroadcast) {
                    rx.resultData = n.toString()
                    rx.resultCode = android.app.Activity.RESULT_OK
                }
                Logx.i("control: imported $n folder(s) - restart the launcher to see them")
            }
            else -> return false
        }
        return true
    }

    private fun handle(intent: Intent) {
        val method = intent.getStringExtra("m") ?: run { Logx.i("control: missing --es m <method>"); return }
        if (method in PERSISTS_STATE && !intent.getBooleanExtra("force", false)) {
            Logx.e("control: '$method' writes persistent launcher state (taskbar_is_stashed) " +
                "and affects BOTH displays. Re-send with --ez force true if that is intended.")
            return
        }
        val ctx = dpContext.get() ?: run { Logx.e("control: external taskbar context not captured yet"); return }
        if (method !in ALLOWED &&
            !Flags.readConfig(ctx as? Context).bool("controlReflect", false)
        ) {
            Logx.e("control: '$method' refused - reflective invoke is off (controlReflect=1 to enable)")
            return
        }

        main.post {
            Logx.guard("control invoke $method") {
                if (method == "dump") {
                    dump(ctx)
                    dumpStash(ctx)
                    Logx.i("  autohide ${autoHide?.describe() ?: "<not attached>"}")
                    return@guard
                }
                if (method == "viewdump") { dumpViews(ctx); return@guard }
                when (method) {
                    "collapse" -> { autoHide?.collapse(); Logx.i("control: collapse"); return@guard }
                    "expand" -> { autoHide?.expand(); Logx.i("control: expand"); return@guard }
                    // Sent by the settings UI after a change. Re-reads every config layer
                    // and reports what this process can actually see, so a setting that
                    // did nothing can be told apart from one that never arrived (trap 6.9).
                    // Only values consulted at runtime re-apply; anything that decides
                    // whether a hook is installed still needs a launcher restart.
                    io.laelaps.zuitweaks.settings.Channels.CMD_RELOAD -> {
                        val fresh = Flags.readConfig(ctx as? Context)
                        Logx.i("control: reload config[$fresh]")
                        Logx.i("control: sources ${Flags.configSourceReport(ctx as? Context)}")
                        autoHide?.reload(fresh)
                        return@guard
                    }
                    // Sent by VirtualCursorHook from com.zui.wifip2p, which owns the only
                    // knowledge of where ZUI's app-drawn cursor actually is.
                    "edgeEnter" -> { autoHide?.onCursorEnter(); return@guard }
                    "edgeExit" -> { autoHide?.onCursorExit(); return@guard }
                }
                // "stash:foo" targets the TaskbarStashController instead of the context.
                if (method.startsWith("stash:")) {
                    val target = stashController(ctx) ?: run { Logx.e("no stash controller"); return@guard }
                    val name = method.removePrefix("stash:")
                    val r = when {
                        intent.hasExtra("i") -> XposedHelpers.callMethod(target, name, intent.getIntExtra("i", 0))
                        intent.hasExtra("b") -> XposedHelpers.callMethod(target, name, intent.getBooleanExtra("b", false))
                        else -> XposedHelpers.callMethod(target, name)
                    }
                    Logx.i("control: stash.$name -> $r")
                    dump(ctx)
                    dumpStash(ctx)
                    return@guard
                }
                val result = when {
                    intent.hasExtra("i") ->
                        XposedHelpers.callMethod(ctx, method, intent.getIntExtra("i", 0))
                    intent.hasExtra("b") ->
                        XposedHelpers.callMethod(ctx, method, intent.getBooleanExtra("b", false))
                    else -> XposedHelpers.callMethod(ctx, method)
                }
                Logx.i("control: $method -> $result")
                dump(ctx)
            }
        }
    }

    /**
     * Walks the taskbar's view tree. If the whole bar looks soft - nav buttons and the
     * all-apps button as well as the app icons - the cause is a scale applied above the
     * icons rather than the icon rasters, and that shows up here as a scaleX/scaleY
     * other than 1 on some ancestor, or as drawables whose intrinsic size does not match
     * the view they are drawn into.
     */
    private fun dumpViews(ctx: Any) {
        val root = XposedHelpers.callMethod(ctx, "getDragLayer") as? android.view.View ?: run {
            Logx.e("viewdump: no drag layer"); return
        }
        val res = root.context.resources
        Logx.i("viewdump: density=${res.displayMetrics.density} densityDpi=${res.displayMetrics.densityDpi} " +
            "display=${root.context.display?.displayId}")
        walk(root, 0)
    }

    private fun walk(v: android.view.View, depth: Int) {
        if (depth > 6) return
        val pad = "  ".repeat(depth)
        val scale = if (v.scaleX != 1f || v.scaleY != 1f) " scale=${v.scaleX}x${v.scaleY}" else ""
        val extra = (v as? android.widget.ImageView)?.drawable?.let {
            " drawable=${it.javaClass.simpleName} intrinsic=${it.intrinsicWidth}x${it.intrinsicHeight}"
        } ?: ""
        if (v.width > 0 || scale.isNotEmpty()) {
            Logx.i("$pad${v.javaClass.simpleName} ${v.width}x${v.height}$scale alpha=${v.alpha}$extra")
        }
        (v as? android.view.ViewGroup)?.let { g ->
            for (i in 0 until g.childCount) walk(g.getChildAt(i), depth + 1)
        }
    }

    /** Walks TaskbarActivityContext.getControllers() and picks the stash controller by type. */
    private fun stashController(ctx: Any): Any? = try {
        val controllers = XposedHelpers.callMethod(ctx, "getControllers")
        controllers.javaClass.declaredFields
            .asSequence()
            .filter { it.type.name.endsWith("TaskbarStashController") }
            .mapNotNull { it.isAccessible = true; it.get(controllers) }
            .firstOrNull()
    } catch (t: Throwable) {
        Logx.e("could not reach stash controller", t)
        null
    }

    private fun dumpStash(ctx: Any) {
        val sc = stashController(ctx) ?: return
        for (name in listOf(
            "isStashed", "isInApp", "isStashedInApp", "isOnHome", "isInOverview",
            "supportsVisualStashing", "supportsManualStashing", "isForceHideTaskbar",
            "isInStashedLauncherState", "isTaskbarVisibleAndNotStashing", "isDeviceLocked",
        )) {
            val value = try {
                XposedHelpers.callMethod(sc, name).toString()
            } catch (t: Throwable) {
                "<${t.javaClass.simpleName}>"
            }
            Logx.i("  stash $name = $value")
        }
        // Every primitive field, so the flag bitmask and the applied-stash boolean
        // can be spotted by watching which ones move between dumps. Static finals
        // (the FLAG_* constants) are skipped - they never change.
        sc.javaClass.declaredFields.forEach { f ->
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) return@forEach
            val t = f.type
            if (!t.isPrimitive) return@forEach
            f.isAccessible = true
            runCatching {
                val v = f.get(sc)
                val shown = when (v) {
                    is Int -> "0x${v.toString(16)} ($v)"
                    is Long -> "0x${v.toString(16)} ($v)"
                    else -> v.toString()
                }
                Logx.i("  stash ${t.simpleName} ${f.name} = $shown")
            }
        }
    }

    private fun dump(ctx: Any) {
        for (name in listOf("isTaskbarStashed", "isTransientTaskbar", "isPinnedTaskbar", "isThreeButtonNav", "getWindowHeight")) {
            val value = try {
                XposedHelpers.callMethod(ctx, name).toString()
            } catch (t: Throwable) {
                "<${t.javaClass.simpleName}>"
            }
            Logx.i("  state $name = $value")
        }
    }
}
