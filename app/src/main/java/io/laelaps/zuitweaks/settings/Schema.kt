package io.laelaps.zuitweaks.settings

/**
 * Every user-facing setting, declared once.
 *
 * This file is read from BOTH sides:
 *  - the app process, to build the UI and to write SharedPreferences
 *  - the hook processes, to know which keys to lift out of those preferences
 *
 * It therefore obeys the hook-process rule from SESSION.md 3: Kotlin stdlib only. In
 * particular there is no reference to R here. AGP generates non-final resource ids, so
 * they are real field reads rather than inlined constants, and a schema that mentioned
 * R would drag the R classes into com.zui.launcher and Gboard for no reason. The display
 * strings live in [Labels], which only the UI touches.
 *
 * Defaults MUST match the defaults hardcoded at each call site in xposed/. They are
 * duplicated on purpose: a hook whose process cannot read the preferences at all still
 * has to come up with the right behaviour (trap 6.9).
 */

/** UI section. */
enum class Group { TASKBAR, ICONS, KEYBOARD, LAUNCHER, TUNING, MISC }

/**
 * Which hooked process consumes the key. Changing a setting only takes effect when that
 * process restarts (trap 6.12), so the UI has to name it - and, given root, do it.
 *
 * [restartScript] runs on the device in a root shell; [adbCmd] is the same thing for a
 * human to paste, shown only when root is refused or unavailable. The touchpad entry is
 * the reason root is needed rather than a granted permission: com.zui.wifip2p runs as the
 * system UID, where force-stop is ignored and only a signal ends the process.
 */
enum class Proc(val restartScript: String, val adbCmd: String) {
    LAUNCHER(
        "am force-stop com.zui.launcher",
        "adb shell am force-stop com.zui.launcher",
    ),
    TOUCHPAD(
        "kill -9 \$(pidof com.zui.wifip2p)",
        "adb shell su -c \"kill -9 \$(pidof com.zui.wifip2p)\"",
    ),
    IME(
        "am force-stop com.google.android.inputmethod.latin",
        "adb shell am force-stop com.google.android.inputmethod.latin",
    ),
}

sealed class Setting(
    val key: String,
    val group: Group,
    val procs: List<Proc>,
    /**
     * True only where the hook re-reads the value at runtime, so Control's reload command
     * is enough. Everything else is consulted once, while deciding whether to install a
     * hook at all, and needs the process restarted (trap 6.12).
     */
    val live: Boolean,
) {
    /** Preferences hold strings, because Flags.Config is a string map. */
    abstract fun defaultAsString(): String
}

class Toggle(
    key: String,
    val default: Boolean,
    group: Group,
    procs: List<Proc>,
    live: Boolean = false,
) : Setting(key, group, procs, live) {
    override fun defaultAsString() = if (default) "1" else "0"
}

class NumberSetting(
    key: String,
    val default: Int,
    val min: Int,
    val max: Int,
    val step: Int,
    group: Group,
    procs: List<Proc>,
    live: Boolean = false,
) : Setting(key, group, procs, live) {
    override fun defaultAsString() = default.toString()

    fun clamp(v: Int): Int =
        v.coerceIn(min, max).let { min + ((it - min + step / 2) / step) * step }.coerceAtMost(max)
}

object Schema {

    private val L = listOf(Proc.LAUNCHER)
    private val I = listOf(Proc.IME)
    private val T = listOf(Proc.TOUCHPAD)
    private val LT = listOf(Proc.LAUNCHER, Proc.TOUCHPAD)
    private val LIT = listOf(Proc.LAUNCHER, Proc.IME, Proc.TOUCHPAD)

    val all: List<Setting> = listOf(

        // ---- taskbar ------------------------------------------------------------
        Toggle("autohide", true, Group.TASKBAR, L),
        Toggle("stripInsets", true, Group.TASKBAR, L),
        Toggle("secondaryHomeGuard", true, Group.TASKBAR, L),
        Toggle("tapToReveal", true, Group.TASKBAR, L),
        Toggle("transient", false, Group.TASKBAR, L),

        // ---- icons --------------------------------------------------------------
        Toggle("iconFix", true, Group.ICONS, L),
        NumberSetting("iconHeadroom", 115, 100, 200, 5, Group.ICONS, L),

        // ---- keyboard -----------------------------------------------------------
        Toggle("altFix", false, Group.KEYBOARD, I),
        Toggle("altDefer", true, Group.KEYBOARD, I),
        NumberSetting("altDeferTimeoutMs", 400, 0, 2000, 50, Group.KEYBOARD, I),

        // ---- tuning -------------------------------------------------------------
        // The two AutoHideController.reload() re-reads, hence live = true.
        // Max is 12 because that is where it was measured to stop being clean: on the
        // external panel 1..12 shows nothing at all, 13 puts one row of the taskbar on
        // screen, and each further pixel adds another row until real icon colour appears
        // around 17. The old ceiling of 40 was not from anything.
        NumberSetting("collapsedPx", 2, 1, 12, 1, Group.TUNING, L, live = true),
        NumberSetting("collapseDelayMs", 1200, 0, 5000, 100, Group.TUNING, L, live = true),
        NumberSetting("cursorHotZonePx", 8, 1, 64, 1, Group.TUNING, LT),
        NumberSetting("cursorKeepOpenPx", 200, 32, 600, 8, Group.TUNING, LT),

        // ---- launcher (drawer folders / reorder) --------------------------------
        // Drag-reorder deltas (defaults match the hardcoded values in DrawerLongPressHook).
        // Consulted at drag time — restart the launcher to apply.
        // The drawer-grouping feature itself. These decide whether the hooks are installed at
        // all, so they are consulted once per launcher start and need a restart to apply.
        //
        // They used to be reachable ONLY from /sdcard/zuitweaks.conf, which is why a single
        // failed file read switched the whole feature off with no way to turn it back on from
        // the app: at boot the launcher starts before external storage is readable by it, the
        // file layer comes back empty, and every key here falls to its default (measured
        // 2026-09-04 - "file=unreadable" in the launcher, "readable(4 keys)" in the touchpad
        // process at the same moment). The module's own preferences ARE readable there, so
        // declaring the keys in the schema puts them on a channel that survives a reboot.
        Toggle("drawerMovable", true, Group.LAUNCHER, L),
        Toggle("folderUi", true, Group.LAUNCHER, L),
        Toggle("drawerOrder", true, Group.LAUNCHER, L),
        NumberSetting("drawerDragSlop", 40, 5, 120, 5, Group.LAUNCHER, L),
        NumberSetting("folderDragSlop", 30, 5, 120, 5, Group.LAUNCHER, L),
        NumberSetting("groupZonePct", 36, 0, 80, 4, Group.LAUNCHER, L),
        // Icons along one side of the drawer folder's preview: 2 -> 2x2, 3 -> 3x3, 4 -> 4x4.
        NumberSetting("folderPreviewGrid", 2, 2, 4, 1, Group.LAUNCHER, L),

        // ---- misc ---------------------------------------------------------------
        // Consulted once when the touchpad hook decides whether to install, so restarting
        // com.zui.wifip2p is what applies a change (not live).
        Toggle("touchpadPortrait", true, Group.MISC, T),
        Toggle("virtualCursor", true, Group.MISC, T),
        Toggle("control", true, Group.MISC, L),
        // Every hooked process writes its own file, so this one is owned by all of them.
        Toggle("debugLog", false, Group.MISC, LIT),
    )

    val byGroup: List<Pair<Group, List<Setting>>> =
        Group.entries.map { g -> g to all.filter { it.group == g } }

    fun byKey(key: String): Setting? = all.firstOrNull { it.key.equals(key, ignoreCase = true) }

    /** Keys the hooks look for, lowercased the way Flags.Config normalises them. */
    val keysLowercase: List<String> = all.map { it.key.lowercase() }
}
