package io.laelaps.zuitweaks.settings

import android.content.Context
import android.provider.Settings
import java.io.File

/**
 * The config channels that are NOT the module's own preferences, and the constants that
 * name them.
 *
 * Split out of Flags on purpose. Flags imports XSharedPreferences, and the Xposed API is
 * a `compileOnly` dependency - those classes are absent from the APK at runtime. Inside a
 * hooked process LSPosed supplies them; inside our own app process nothing does, so the
 * settings UI touching Flags would throw NoClassDefFoundError. Everything the UI needs
 * lives here instead, and Flags delegates to it.
 *
 * Nothing in this file may reference de.robv.android.xposed.
 */
object Channels {

    /**
     * The launcher can read /sdcard but not /data/local/tmp (SELinux) - measured in
     * Phase D, do not "simplify" this back to /data/local/tmp.
     */
    const val KILL_SWITCH_PATH = "/sdcard/zuitweaks.off"
    const val CONFIG_FILE_PATH = "/sdcard/zuitweaks.conf"

    /** Readable by any process without a permission, unlike the file. See trap 6.9. */
    const val SETTINGS_KEY = "zuitweaks_conf"

    /**
     * The process Control's receiver lives in. Named here rather than only in Flags so the
     * settings UI can address its broadcast without touching an Xposed-referencing class.
     */
    const val TARGET_PKG = "com.zui.launcher"

    /** Control's broadcast, registered inside com.zui.launcher by Control.register(). */
    const val CONTROL_ACTION = "io.laelaps.zuitweaks.CMD"

    /** Control command that re-reads every layer and re-applies what is live-tunable. */
    const val CMD_RELOAD = "config"

    /** Extra name Control reads the command from. */
    const val EXTRA_METHOD = "m"

    /**
     * Control commands for the drawer-folder state.
     *
     * Grouping lives in a SQLite database inside com.zui.launcher's own data directory, which
     * this app cannot read - so the settings file can only carry folders by asking the launcher
     * for them. Export is an ORDERED broadcast and the answer comes back as the result data;
     * import hands the lines over in [EXTRA_DATA].
     */
    const val CMD_FOLDERS_EXPORT = "folders:export"
    const val CMD_FOLDERS_IMPORT = "folders:import"

    /** Extra carrying the folder lines of [CMD_FOLDERS_IMPORT]. */
    const val EXTRA_DATA = "d"

    /**
     * Whether a path is readable is a per-process question, so this is a tri-state rather
     * than a boolean. Our own app is an ordinary unprivileged process and cannot read
     * /sdcard without storage permissions - it will normally see UNREADABLE, which is not
     * the same as "no override is set".
     */
    enum class Reach { PRESENT, ABSENT, UNREADABLE }

    fun reachOf(path: String): Reach = try {
        val f = File(path)
        when {
            f.isFile && f.canRead() -> Reach.PRESENT
            f.exists() -> Reach.UNREADABLE
            f.parentFile?.canRead() == true -> Reach.ABSENT
            else -> Reach.UNREADABLE
        }
    } catch (t: Throwable) {
        Reach.UNREADABLE
    }

    fun killSwitchPresent(): Boolean = try {
        File(KILL_SWITCH_PATH).exists()
    } catch (t: Throwable) {
        false
    }

    private fun parse(lines: List<String>): Map<String, String> =
        lines.map { it.substringBefore('#').trim() }
            .filter { it.contains('=') }
            .associate {
                it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim().lowercase()
            }

    /** Layer 2. Empty both when unset and when this process cannot read it - see [reachOf]. */
    fun readFileLayer(): Map<String, String> = try {
        val f = File(CONFIG_FILE_PATH)
        if (f.isFile) parse(f.readLines()) else emptyMap()
    } catch (t: Throwable) {
        emptyMap()
    }

    /** Layer 3. Accepts newline- or semicolon-separated pairs. */
    fun readSettingsLayer(context: Context): Map<String, String> = try {
        Settings.Global.getString(context.contentResolver, SETTINGS_KEY)
            ?.split('\n', ';')
            ?.let { parse(it) }
            ?: emptyMap()
    } catch (t: Throwable) {
        emptyMap()
    }

    /**
     * Debug entries that are actually in effect: the keys these channels carry that the schema
     * does NOT own. A schema key found here is ignored by [io.laelaps.zuitweaks.xposed.Flags],
     * because a setting with a toggle belongs to the app's preferences alone - so listing one
     * would claim an override that does not happen.
     */
    fun debugOverrides(context: Context?): Map<String, String> {
        val over = LinkedHashMap<String, String>()
        over.putAll(readFileLayer())
        if (context != null) over.putAll(readSettingsLayer(context))
        return over.filterKeys { it !in Schema.keysLowercase }
    }
}
