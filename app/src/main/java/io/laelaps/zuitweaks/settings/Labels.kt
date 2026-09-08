package io.laelaps.zuitweaks.settings

import io.laelaps.zuitweaks.R

/**
 * Display strings for [Schema], kept apart from it on purpose.
 *
 * Schema is loaded inside com.zui.launcher, com.zui.wifip2p and the IME. Resource ids are
 * non-final fields under AGP, so a schema that named R would pull the R classes into every
 * one of those processes. Nothing here is ever touched from a hook.
 *
 * SettingsTest asserts that every schema key has an entry, so the two files cannot drift.
 */
object Labels {

    class Text(val titleRes: Int, val summaryRes: Int, val unitRes: Int = 0)

    fun of(group: Group): Int = when (group) {
        Group.TASKBAR -> R.string.group_taskbar
        Group.ICONS -> R.string.group_icons
        Group.KEYBOARD -> R.string.group_keyboard
        Group.LAUNCHER -> R.string.group_launcher
        Group.TUNING -> R.string.group_tuning
        Group.MISC -> R.string.group_misc
    }

    fun of(proc: Proc): Int = when (proc) {
        Proc.LAUNCHER -> R.string.proc_launcher
        Proc.TOUCHPAD -> R.string.proc_touchpad
        Proc.IME -> R.string.proc_ime
    }

    private val byKey: Map<String, Text> = mapOf(
        "autohide" to Text(R.string.set_autohide_title, R.string.set_autohide_summary),
        "stripInsets" to Text(R.string.set_stripinsets_title, R.string.set_stripinsets_summary),
        "secondaryHomeGuard" to Text(R.string.set_secondaryhomeguard_title, R.string.set_secondaryhomeguard_summary),
        "tapToReveal" to Text(R.string.set_taptoreveal_title, R.string.set_taptoreveal_summary),
        "transient" to Text(R.string.set_transient_title, R.string.set_transient_summary),

        "iconFix" to Text(R.string.set_iconfix_title, R.string.set_iconfix_summary),
        "iconHeadroom" to Text(
            R.string.set_iconheadroom_title, R.string.set_iconheadroom_summary, R.string.unit_percent,
        ),

        "altFix" to Text(R.string.set_altfix_title, R.string.set_altfix_summary),
        "altDefer" to Text(R.string.set_altdefer_title, R.string.set_altdefer_summary),
        "altDeferTimeoutMs" to Text(
            R.string.set_altdefertimeout_title, R.string.set_altdefertimeout_summary, R.string.unit_ms,
        ),

        "collapsedPx" to Text(
            R.string.set_collapsedpx_title, R.string.set_collapsedpx_summary, R.string.unit_px,
        ),
        "collapseDelayMs" to Text(
            R.string.set_collapsedelay_title, R.string.set_collapsedelay_summary, R.string.unit_ms,
        ),
        "cursorHotZonePx" to Text(
            R.string.set_cursorhotzone_title, R.string.set_cursorhotzone_summary, R.string.unit_px,
        ),
        "cursorKeepOpenPx" to Text(
            R.string.set_cursorkeepopen_title, R.string.set_cursorkeepopen_summary, R.string.unit_px,
        ),
        "drawerMovable" to Text(R.string.set_drawermovable_title, R.string.set_drawermovable_summary),
        "folderUi" to Text(R.string.set_folderui_title, R.string.set_folderui_summary),
        "folderPreviewGrid" to Text(R.string.set_folderpreviewgrid_title, R.string.set_folderpreviewgrid_summary),
        "drawerOrder" to Text(R.string.set_drawerorder_title, R.string.set_drawerorder_summary),

        "drawerDragSlop" to Text(
            R.string.set_drawerdragslop_title, R.string.set_drawerdragslop_summary, R.string.unit_px,
        ),
        "folderDragSlop" to Text(
            R.string.set_folderdragslop_title, R.string.set_folderdragslop_summary, R.string.unit_px,
        ),
        "groupZonePct" to Text(
            R.string.set_groupzonepct_title, R.string.set_groupzonepct_summary, R.string.unit_percent,
        ),

        "touchpadPortrait" to Text(
            R.string.set_touchpadportrait_title, R.string.set_touchpadportrait_summary,
        ),
        "virtualCursor" to Text(R.string.set_virtualcursor_title, R.string.set_virtualcursor_summary),
        "control" to Text(R.string.set_control_title, R.string.set_control_summary),
        "debugLog" to Text(R.string.set_debuglog_title, R.string.set_debuglog_summary),
    )

    /** Keys present here, for the completeness test. */
    val keys: Set<String> get() = byKey.keys

    fun of(setting: Setting): Text = byKey.getValue(setting.key)
}
