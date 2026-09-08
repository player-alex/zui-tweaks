package io.laelaps.zuitweaks.xposed

import android.content.Context
import android.content.Intent
import java.lang.ref.WeakReference

/**
 * Cursor detection for ZUI's "tablet as a virtual touchpad" feature.
 *
 * That cursor is not a pointer. Moving it injects *nothing*: the app simply repositions
 * an ImageView in a TYPE_SECURE_SYSTEM_OVERLAY window it owns. Taps are injected as
 * touchscreen-source events (`source=0x1002`, `toolType=FINGER`), and Android never
 * synthesises ACTION_HOVER_* for a touchscreen source - so no amount of hooking in the
 * launcher can see this cursor. Measured and confirmed against the APK; see RECON.md.
 *
 * `TouchPad.updateViewPos(x, y)` is called on every cursor move with the coordinates
 * already in the external display's space, which is exactly the signal we need. Only
 * hot-zone transitions are forwarded to the launcher, so a fast drag across the screen
 * costs at most two broadcasts.
 *
 * This hook runs inside com.zui.wifip2p, not the launcher.
 */
object VirtualCursorHook {

    private const val TOUCHPAD_CLASS = "com.zui.wifip2p.touchpad.TouchPad"

    private var hotZonePx = 8
    private var keepOpenPx = 200
    private var inHotZone = false
    private var logged = 0

    /** Last TouchPad seen, so [reset] has something to broadcast from. Weak: the app owns it. */
    private var lastTouchPad = WeakReference<Any>(null)

    fun install(classLoader: ClassLoader, config: Flags.Config) {
        hotZonePx = config.int("cursorHotZonePx", 8)
        keepOpenPx = config.int("cursorKeepOpenPx", 200)
        try {
            XposedHelpers.findAndHookMethod(
                TOUCHPAD_CLASS, classLoader, "updateViewPos",
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("updateViewPos") {
                            onCursorMoved(
                                param.thisObject,
                                (param.args[1] as Number).toFloat(),
                            )
                        }
                    }
                },
            )
            Logx.i("virtual cursor: hooked $TOUCHPAD_CLASS.updateViewPos, enter<=${hotZonePx}px exit>${keepOpenPx}px")
        } catch (t: Throwable) {
            Logx.e("virtual cursor: could not hook $TOUCHPAD_CLASS.updateViewPos", t)
        }
    }

    /**
     * The touchpad went away (activity paused) while the cursor was still in the hot zone.
     * The launcher is holding an edgeEnter that nothing is going to match, so send the exit
     * ourselves and drop the latch - the next session starts from a known state.
     */
    fun reset() {
        val wasInside = inHotZone
        inHotZone = false
        if (!wasInside) return
        val pad = lastTouchPad.get() ?: run {
            Logx.i("virtual cursor: reset with no touchpad instance - latch cleared only")
            return
        }
        signal(pad, "edgeExit")
    }

    private fun onCursorMoved(touchPad: Any, y: Float) {
        lastTouchPad = WeakReference(touchPad)
        // The app stores the target display and its logical size when the touchpad opens.
        val displayId = intField(touchPad, "expandDisplayId") ?: return
        if (displayId == 0) return
        val displayHeight = intField(touchPad, "displayRectY")?.takeIf { it > 0 } ?: return

        val distanceFromBottom = displayHeight - y

        if (logged < 8) {
            logged++
            Logx.i("cursor: display=$displayId y=$y of $displayHeight (bottom-$distanceFromBottom)")
        }

        // Hysteresis. Entering takes a few pixels at the very edge, but once the bar is
        // up it occupies ~162px, and reporting "left the hot zone" the moment the cursor
        // rises onto the bar itself would close it under the user's pointer. A real mouse
        // does not have this problem because the bar's own window keeps receiving hover;
        // here the taskbar never sees this cursor at all, so the band has to be modelled.
        val nowInside = if (inHotZone) distanceFromBottom <= keepOpenPx else distanceFromBottom <= hotZonePx
        if (nowInside == inHotZone) return
        inHotZone = nowInside
        signal(touchPad, if (nowInside) "edgeEnter" else "edgeExit")
    }

    /** The field may be declared int or float depending on build; accept either. */
    private fun intField(target: Any, name: String): Int? = try {
        (XposedHelpers.getObjectField(target, name) as? Number)?.toInt()
    } catch (t: Throwable) {
        try {
            XposedHelpers.getIntField(target, name)
        } catch (t2: Throwable) {
            Logx.e("virtual cursor: no field '$name' on ${target.javaClass.name}", t2)
            null
        }
    }

    private fun signal(touchPad: Any, command: String) {
        try {
            val ctx = contextOf(touchPad) ?: run {
                Logx.e("virtual cursor: no context to broadcast $command from")
                return
            }
            ctx.sendBroadcast(
                Intent(Control.ACTION)
                    .putExtra("m", command)
                    .setPackage(Flags.TARGET_PKG)
            )
            Logx.i("virtual cursor: $command")
        } catch (t: Throwable) {
            Logx.e("virtual cursor: could not send $command", t)
        }
    }

    /** TouchPad is not a Context; find the one it holds. */
    private fun contextOf(touchPad: Any): Context? {
        for (field in touchPad.javaClass.declaredFields) {
            if (!Context::class.java.isAssignableFrom(field.type)) continue
            try {
                field.isAccessible = true
                (field.get(touchPad) as? Context)?.let { return it }
            } catch (t: Throwable) {
                // keep looking
            }
        }
        return try {
            XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"), "currentApplication"
            ) as? Context
        } catch (t: Throwable) {
            null
        }
    }
}
