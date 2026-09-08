package io.laelaps.zuitweaks.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * The app-side half of the settings pipe.
 *
 * The hooks do not run in this app - they run inside com.zui.launcher, com.zui.wifip2p
 * and the IME. They cannot open our private SharedPreferences, which is the whole reason
 * /sdcard/zuitweaks.conf existed. LSPosed bridges this: with the `xposedsharedprefs`
 * meta-data in the manifest it intercepts getSharedPreferences(.., MODE_WORLD_READABLE)
 * in this process and stores the file where a hooked process can read it back through
 * XSharedPreferences (see Flags.readModulePrefs).
 *
 * If LSPosed is not active the MODE_WORLD_READABLE request throws, we fall back to a
 * private file, and [lsposedActive] is false - the UI says so rather than pretending the
 * toggles do anything.
 */
class SettingsStore private constructor(
    private val prefs: SharedPreferences,
    /** False means the settings written here are not reaching any hook. */
    val lsposedActive: Boolean,
) {

    companion object {
        /** Also hardcoded in Flags.PREFS_NAME, which cannot reference this class safely. */
        const val NAME = "settings"

        /**
         * Written on first open purely so the file exists. Not a setting - it is deliberately
         * absent from [Schema], so the hooks never read it and it shadows nothing.
         *
         * getSharedPreferences() does not create anything; SharedPreferences materialises the
         * file on the first write. Without this marker the file stays missing until the user
         * happens to change something, and until then every hook reports the preferences as
         * `absent` - which is indistinguishable from the LSPosed bridge being broken. That
         * makes the one diagnostic that answers trap 6.9 useless exactly when it is needed.
         */
        /** Accepted spellings of "on", matching Flags.Config.bool in the hook processes. */
        private val TRUE_WORDS = setOf("1", "true", "yes", "on")

        private const val KEY_SCHEMA = "_schema"
        private const val SCHEMA_VERSION = 1

        fun open(context: Context): SettingsStore {
            // libXposed API 101: preferences travel through the framework's remote-preferences
            // store, bound to this app via the bundled XposedProvider (see RemotePrefs). When the
            // module is active we read/write there and the hooks see it through
            // XposedModule.getRemotePreferences(NAME); otherwise fall back to a private file and
            // report inactive so the UI shows the "LSPosed is not passing these settings on" banner.
            val remote = RemotePrefs.open(NAME)
            val store = if (remote != null) {
                SettingsStore(remote, true)
            } else {
                SettingsStore(context.getSharedPreferences(NAME, Context.MODE_PRIVATE), false)
            }
            store.materialise()
            return store
        }
    }

    /**
     * commit(), not apply(): the point is that the file is on disk before the next hooked
     * process starts and tries to read it. It writes once, on the first launch after install.
     */
    private fun materialise() {
        if (prefs.getInt(KEY_SCHEMA, -1) == SCHEMA_VERSION) return
        prefs.edit().putInt(KEY_SCHEMA, SCHEMA_VERSION).commit()
    }

    fun bool(s: Toggle): Boolean = when (prefs.getString(s.key, null)) {
        null -> s.default
        "1", "true", "yes", "on" -> true
        else -> false
    }

    fun int(s: NumberSetting): Int = prefs.getString(s.key, null)?.toIntOrNull() ?: s.default

    fun set(s: Toggle, value: Boolean) = write(s.key, if (value) "1" else "0")

    fun set(s: NumberSetting, value: Int) = write(s.key, s.clamp(value).toString())

    /** Drops the entry entirely, so the hook's own hardcoded default wins again. */
    fun clear(s: Setting) = prefs.edit().remove(s.key).apply()

    fun resetAll() = prefs.edit().apply { Schema.all.forEach { remove(it.key) } }.apply()

    fun isDefault(s: Setting): Boolean = when (s) {
        is Toggle -> bool(s) == s.default
        is NumberSetting -> int(s) == s.default
    }

    /** Everything explicitly set, in the `key=value` shape the hooks already parse. */
    fun asConfigMap(): Map<String, String> =
        Schema.all.mapNotNull { s -> prefs.getString(s.key, null)?.let { s.key.lowercase() to it } }.toMap()

    /**
     * Every setting and its current value as `key=value` lines - a snapshot, defaults included,
     * so a restored file reproduces this device rather than only the parts that happen to differ.
     *
     * This is the ONLY file the settings ever travel through, and it moves only when the user
     * asks. Nothing reads a file at runtime: the hooks take schema settings from these
     * preferences alone (see Flags.readConfig).
     */
    fun exportText(): String = buildString {
        appendLine("# ZUI Tweaks settings")
        appendLine("# Import this from the app. Not read at runtime.")
        for (s in Schema.all) {
            val value = when (s) {
                is Toggle -> if (bool(s)) "1" else "0"
                is NumberSetting -> int(s).toString()
            }
            appendLine(s.key + "=" + value)
        }
    }

    /** Applies the recognised `key=value` lines; returns how many settings were written. */
    fun importText(text: String): Int {
        var applied = 0
        val editor = prefs.edit()
        for (line in text.lineSequence()) {
            val body = line.substringBefore('#').trim()
            if (!body.contains('=')) continue
            val setting = Schema.byKey(body.substringBefore('=').trim()) ?: continue
            val raw = body.substringAfter('=').trim()
            val value = when (setting) {
                is Toggle -> if (raw.lowercase() in TRUE_WORDS) "1" else "0"
                is NumberSetting -> {
                    val parsed = raw.toIntOrNull() ?: continue
                    setting.clamp(parsed).toString()
                }
            }
            editor.putString(setting.key, value)
            applied++
        }
        // commit(), like materialise(): the values must be on disk before the process that
        // reads them is restarted, which is the very next thing the user is told to do.
        editor.commit()
        return applied
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    private fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
