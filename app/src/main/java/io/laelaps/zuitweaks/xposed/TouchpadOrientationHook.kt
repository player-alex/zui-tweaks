package io.laelaps.zuitweaks.xposed

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle

/**
 * Lets ZUI's virtual-touchpad turn to portrait.
 *
 * `TouchPadActivity` is pinned to landscape by a single manifest attribute
 * (`android:screenOrientation` = SENSOR_LANDSCAPE). There is no runtime orientation code to fight -
 * no `setRequestedOrientation`, no rotation reads - and the touch mapping is *relative*: it
 * accumulates scaled `getX()/getY()` deltas (in the activity's own rotated frame) and clamps them to
 * the *external* display's bounds, then repositions the cursor overlay on that display. So the
 * cursor math does not depend on the tablet's orientation, and letting the activity rotate needs no
 * coordinate patch. The whole fix is to override the requested orientation once the activity exists.
 *
 * FULL_SENSOR (10) extends the existing SENSOR_LANDSCAPE (6) to all four orientations, so physically
 * turning the tablet rotates the touch surface - portrait included. Tunable via `touchpadOrientation`
 * (e.g. 13 FULL_USER to honour the auto-rotate lock, -1 UNSPECIFIED for system default).
 *
 * Runs inside com.zui.wifip2p (same process as VirtualCursorHook). See RECON.md 4 and the touchpad
 * analysis in SESSION.md (2026-08-27).
 */
object TouchpadOrientationHook {

    private const val TOUCHPAD_ACTIVITY = "com.zui.wifip2p.touchpad.TouchPadActivity"

    fun install(classLoader: ClassLoader, config: Flags.Config) {
        val orientation =
            config.int("touchpadOrientation", ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR)

        // TouchPadActivity need not declare onCreate/onResume/onPause itself. The shim then
        // resolves the hook onto a base activity class, and the callback would fire for every
        // other activity in this process too - so the receiver's type is checked first.
        val target = XposedHelpers.findClass(TOUCHPAD_ACTIVITY, classLoader)

        // The activity has no configChanges for orientation, so the first rotation recreates it;
        // that is safe here (onDestroy tears the cursor down, onResume->initDispatchEvent rebuilds
        // it). onResume re-asserts the override as a belt-and-suspenders against any later re-force.
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                Logx.guard("touchpad orientation") {
                    if (!target.isInstance(param.thisObject)) return@guard
                    (param.thisObject as? Activity)?.requestedOrientation = orientation
                }
            }
        }

        // Leaving the touchpad while the cursor sits in the bottom hot zone left the launcher
        // holding an unmatched edgeEnter - the bar stayed open with nothing able to close it,
        // because the next cursor move starts a fresh session. Clear the latch on the way out.
        val onPause = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                Logx.guard("touchpad onPause") {
                    if (!target.isInstance(param.thisObject)) return@guard
                    VirtualCursorHook.reset()
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                TOUCHPAD_ACTIVITY, classLoader, "onCreate", Bundle::class.java, hook,
            )
            XposedHelpers.findAndHookMethod(TOUCHPAD_ACTIVITY, classLoader, "onResume", hook)
            Logx.i("touchpad orientation: unlocked $TOUCHPAD_ACTIVITY -> $orientation")
        } catch (t: Throwable) {
            Logx.e("touchpad orientation: could not hook TouchPadActivity", t)
        }
        try {
            XposedHelpers.findAndHookMethod(TOUCHPAD_ACTIVITY, classLoader, "onPause", onPause)
            Logx.i("touchpad: hot-zone latch cleared on $TOUCHPAD_ACTIVITY.onPause")
        } catch (t: Throwable) {
            Logx.e("touchpad: could not hook TouchPadActivity.onPause", t)
        }
    }
}
