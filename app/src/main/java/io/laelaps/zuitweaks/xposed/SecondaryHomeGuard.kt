package io.laelaps.zuitweaks.xposed

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock

/**
 * Keeps the tablet's own screen (display 0) on the primary home, never the external
 * display's secondary home.
 *
 * The bug, reverse-engineered from ZuiLauncher 18.2.0.0400:
 *  - On an external-display topology change, ZUI's DpModeManager keeps a STALE external
 *    display id: onDisplayRemoved does not clear it, onDisplayAdded/Changed are no-ops, and
 *    it is only reset on the zui_dp_display_pc_mode setting toggle or process death - never
 *    on a physical unplug. So the launcher stays "in DP mode" holding a dead display id
 *    until a reboot (which is why only a reboot cleared it).
 *  - HomeIntentProvider.addLaunchHomePendingIntent() picks HOME vs SECONDARY_HOME by
 *    display id, but pins the launch display only behind
 *    Flags.enablePerDisplayDesktopWallpaperActivity(), which is false on this build. So a
 *    SECONDARY_HOME intent can be dispatched with NO target display, and when the external
 *    display is removed system_server reparents its SECONDARY_HOME task
 *    (SecondaryDisplayLauncher) onto the default display 0.
 *  - SecondaryDisplayLauncher has no self-guard: canStartHomeSafely() returns false and it
 *    never checks getDisplayId(), so once it lands on display 0 it stays there - the tablet
 *    shows wallpaper with no taskbar.
 *
 * This hook is the second line of defence the launcher lacks. If SecondaryDisplayLauncher
 * ever resumes on display 0, it finishes it and brings the user's real home back onto
 * display 0. Self-heals without a reboot; a no-op in every normal arrangement (the
 * secondary launcher legitimately lives on the external display, id != 0).
 */
object SecondaryHomeGuard {

    private const val SECONDARY_LAUNCHER =
        "com.zui.launcher.secondarydisplay.SecondaryDisplayLauncher"

    /** Used only if the active primary home cannot be resolved; it was the observed default. */
    private const val FALLBACK_HOME = "com.zui.launcher.drawer.DrawerLauncher"

    /** Stops a pathological ping-pong if the framework re-routes secondary home to 0 at once. */
    private var lastReclaimUptime = 0L

    fun install(classLoader: ClassLoader) {
        try {
            // SecondaryDisplayLauncher may not declare onResume itself, in which case the
            // shim resolves the hook onto a base class every launcher activity shares -
            // and then this callback would fire for DrawerLauncher on display 0 and put it
            // into a finish()/relaunch loop. The receiver's type is the guard, not the id.
            val target = XposedHelpers.findClass(SECONDARY_LAUNCHER, classLoader)
            XposedHelpers.findAndHookMethod(
                SECONDARY_LAUNCHER, classLoader, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("secondary home guard") {
                            if (!target.isInstance(param.thisObject)) return@guard
                            val activity = param.thisObject as? Activity ?: return@guard
                            val displayId = try {
                                activity.display?.displayId ?: return@guard
                            } catch (t: Throwable) {
                                return@guard
                            }
                            if (displayId != 0) return@guard

                            val now = SystemClock.uptimeMillis()
                            if (now - lastReclaimUptime < DEBOUNCE_MS) return@guard
                            lastReclaimUptime = now

                            Logx.i("secondary home resumed on display 0 - reclaiming for primary home")
                            reclaimDisplayZero(activity)
                        }
                    }
                },
            )
            Logx.i("secondary home guard: watching $SECONDARY_LAUNCHER on display 0")
        } catch (t: Throwable) {
            Logx.e("secondary home guard: could not hook $SECONDARY_LAUNCHER.onResume", t)
        }
    }

    private fun reclaimDisplayZero(activity: Activity) {
        val comp = resolvePrimaryHome(activity)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        if (comp != null) home.component = comp else home.setPackage(Flags.TARGET_PKG)
        home.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

        val opts = ActivityOptions.makeBasic()
        opts.setLaunchDisplayId(0)

        // Bring the real home to the tablet display first, then drop the misplaced
        // secondary home - starting home before finishing avoids a flash of empty display.
        activity.startActivity(home, opts.toBundle())
        activity.finish()
        Logx.i("secondary home guard: launched ${comp?.className ?: "<default home>"} on display 0, finished secondary")
    }

    /**
     * The user's active primary-home component (Normal / Drawer / CustomMode), resolved
     * rather than hardcoded so the fix respects whichever home mode is set. This never
     * returns the secondary launcher: it is registered for SECONDARY_HOME, not HOME, and is
     * filtered out defensively anyway.
     */
    private fun resolvePrimaryHome(activity: Activity): ComponentName? = try {
        val query = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(Flags.TARGET_PKG)
        val ai = activity.packageManager
            .resolveActivity(query, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
        when {
            ai == null -> ComponentName(Flags.TARGET_PKG, FALLBACK_HOME)
            ai.name.contains("SecondaryDisplayLauncher") -> ComponentName(Flags.TARGET_PKG, FALLBACK_HOME)
            else -> ComponentName(ai.packageName, ai.name)
        }
    } catch (t: Throwable) {
        Logx.e("secondary home guard: could not resolve primary home", t)
        ComponentName(Flags.TARGET_PKG, FALLBACK_HOME)
    }

    private const val DEBOUNCE_MS = 3000L
}
