package io.laelaps.zuitweaks.xposed

import android.content.Context
import io.laelaps.zuitweaks.BuildConfig
import io.laelaps.zuitweaks.settings.Channels
import io.laelaps.zuitweaks.settings.Schema

/**
 * Kill switch, runtime config, and measured constants.
 *
 * Values here come from RECON.md, not from guesswork. Re-measure before changing one.
 *
 * Config arrives in three layers, lowest priority first:
 *
 *   1. the module's own SharedPreferences, written by the settings UI and read back
 *      through LSPosed's XSharedPreferences bridge. This is the normal path.
 *   2. /sdcard/zuitweaks.conf         - debug override, readable by the ZUI launcher only
 *   3. Settings.Global/zuitweaks_conf - debug override, readable by ANY process (trap 6.9)
 *
 * Layers 2 and 3 are kept deliberately. They are how a setting gets bisected on device
 * without touching the UI, and layer 3 is the only channel proven to reach an
 * unprivileged process such as the IME. Whether layer 1 reaches that process is a
 * per-process question - use [configSourceReport] to answer it by measurement rather
 * than by assumption.
 *
 * This object imports XSharedPreferences, which is `compileOnly` and therefore absent
 * from the APK. Only hooked processes may touch it. The settings UI uses
 * [io.laelaps.zuitweaks.settings.Channels] instead.
 */
object Flags {

    const val TAG = "ZuiTweaks"

    /** Owner of the external-display taskbar. NOT com.android.systemui - see RECON.md 3. */
    const val TARGET_PKG = Channels.TARGET_PKG

    /**
     * ZUI's "tablet as a virtual touchpad" app. Its cursor is not a real pointer - it is
     * a small overlay window this app repositions itself, so the only way to know where
     * that cursor is, is from inside this process.
     */
    const val TOUCHPAD_PKG = "com.zui.wifip2p"

    /** Window title of the external-display taskbar; the internal one is "Taskbar". */
    const val TARGET_TITLE = "Taskbar_dp"

    /** WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL - @hide, so hardcoded. */
    const val TYPE_NAVIGATION_BAR_PANEL = 2024

    /**
     * The external-display taskbar context. Its plain superclass drives the internal
     * display, so the receiver's type - not the display id - is the safest discriminator
     * for anything reached through TaskbarActivityContext. (RECON.md 2/B)
     */
    const val DP_CONTEXT_CLASS = "com.zui.launcher.taskbar.TaskbarActivityContextDp"

    /** Must equal SettingsStore.NAME. */
    const val PREFS_NAME = "settings"

    /** Kept for callers that still reference it through Flags. */
    const val SETTINGS_KEY = Channels.SETTINGS_KEY

    fun isDisabled(): Boolean = Channels.killSwitchPresent()

    /**
     * Config values as the hooks consume them: a flat string map, keys lowercased.
     * Unknown keys are ignored so the debug file can carry notes.
     */
    class Config(private val map: Map<String, String>) {
        fun bool(key: String, default: Boolean): Boolean =
            when (map[key.lowercase()]) {
                null -> default
                "1", "true", "yes", "on" -> true
                else -> false
            }

        fun int(key: String, default: Int): Int =
            map[key.lowercase()]?.toIntOrNull() ?: default

        fun long(key: String, default: Long): Long =
            map[key.lowercase()]?.toLongOrNull() ?: default

        fun entries(): Map<String, String> = map

        override fun toString(): String =
            if (map.isEmpty()) "<defaults>" else map.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }

    // ---- layer 1: the module's own preferences ----------------------------------------

    private var cachedPrefs: XSharedPreferences? = null

    /**
     * Null when this process cannot see the module's preferences at all - the expected
     * outcome for a sufficiently unprivileged process, and exactly the situation trap 6.9
     * describes. Callers must degrade to their hardcoded default rather than treating
     * "unreadable" as "unset".
     */
    private fun modulePrefs(): XSharedPreferences? {
        cachedPrefs?.let { existing ->
            return try {
                existing.reload()
                if (existing.file.canRead()) existing else null
            } catch (t: Throwable) {
                null
            }
        }
        return try {
            val p = XSharedPreferences(BuildConfig.APPLICATION_ID, PREFS_NAME)
            try {
                @Suppress("DEPRECATION")
                p.makeWorldReadable()
            } catch (t: Throwable) {
                // Newer LSPosed builds handle the mode themselves and this is a no-op.
            }
            // Absent and unreadable mean very different things and were being logged the
            // same way: absent is "the settings UI has not run yet", unreadable is "the
            // LSPosed bridge does not reach this process" (trap 6.9). Only the second one
            // is fatal - an empty-but-readable store simply answers every key with its
            // default, which is what the caller would have done anyway.
            when {
                !p.file.canRead() -> {
                    Logx.i("module prefs unreadable (bridge does not reach here): ${p.file} - hook defaults apply")
                    null
                }
                !p.file.exists() -> {
                    Logx.i("module prefs absent (settings UI has not created it yet): ${p.file} - hook defaults apply")
                    cachedPrefs = p
                    p
                }
                else -> {
                    cachedPrefs = p
                    p
                }
            }
        } catch (t: Throwable) {
            Logx.e("XSharedPreferences unavailable", t)
            null
        }
    }

    /** Only keys declared in the schema; anything else in that file is not ours. */
    private fun readModulePrefs(): Map<String, String> {
        val p = modulePrefs() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (setting in Schema.all) {
            val v = try {
                p.getString(setting.key, null)
            } catch (t: Throwable) {
                null
            }
            if (v != null) out[setting.key.lowercase()] = v.trim().lowercase()
        }
        return out
    }

    // ---- composition -------------------------------------------------------------------

    /**
     * The module's own preferences decide every setting the schema declares. The debug
     * channels may only introduce keys the schema does NOT own.
     *
     * They used to override the preferences, and that cost a working feature: at boot the
     * launcher starts before external storage is readable by it, so the file layer came back
     * empty while the same file read fine in the touchpad process moments later (measured
     * 2026-09-04). Every key then fell to its hardcoded default and the drawer-folder hooks
     * silently never installed, with no way to switch them back on from the app. A setting
     * with a toggle now lives in exactly one place - the app - and a file cannot take it over
     * or lose it. Use Import/Export in the settings screen to move settings as a file.
     *
     * What remains file-settable is the diagnostics: probes and the stash guards, which have
     * no toggle by design and are how a behaviour gets bisected on device.
     */
    fun readConfig(context: Context?): Config {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(readModulePrefs())
        val debug = LinkedHashMap<String, String>()
        debug.putAll(Channels.readFileLayer())
        if (context != null) debug.putAll(Channels.readSettingsLayer(context))
        for ((key, value) in debug) if (key !in Schema.keysLowercase) merged[key] = value
        return Config(merged)
    }

    /**
     * For hooks installed from handleLoadPackage, which runs before any Context exists.
     * Settings.Global is therefore not consulted - pass a Context once one is in hand.
     */
    fun readConfig(): Config = readConfig(null)

    /** Per-path reachability, so a missing kill switch is distinguishable from an unreadable one. */
    fun killSwitchReport(): String =
        listOf(Channels.KILL_SWITCH_PATH, Channels.CONFIG_FILE_PATH).joinToString(", ") { path ->
            "$path=${Channels.reachOf(path).name.lowercase()}"
        }

    /**
     * Which config layers this particular process can actually see. Log it once per
     * process: trap 6.9 was a whole session lost to assuming a channel readable somewhere
     * was readable everywhere.
     */
    fun configSourceReport(context: Context?): String {
        val prefs = try {
            val p = XSharedPreferences(BuildConfig.APPLICATION_ID, PREFS_NAME)
            when {
                !p.file.canRead() -> "unreadable(${p.file})"
                !p.file.exists() -> "absent(${p.file})"
                else -> "readable(${readModulePrefs().size} settings)"
            }
        } catch (t: Throwable) {
            "error:${t.javaClass.simpleName}"
        }
        val file = when (Channels.reachOf(Channels.CONFIG_FILE_PATH)) {
            Channels.Reach.PRESENT -> "readable(${Channels.readFileLayer().size} keys)"
            Channels.Reach.ABSENT -> "absent"
            Channels.Reach.UNREADABLE -> "unreadable"
        }
        val settings = if (context == null) "no-context" else try {
            val n = Channels.readSettingsLayer(context).size
            if (n == 0) "empty" else "readable($n keys)"
        } catch (t: Throwable) {
            "error:${t.javaClass.simpleName}"
        }
        return "prefs=$prefs, file=$file, settings=$settings"
    }
}
