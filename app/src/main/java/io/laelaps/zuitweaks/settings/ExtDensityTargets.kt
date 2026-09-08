package io.laelaps.zuitweaks.settings

import android.content.Context

/**
 * The app-selection allowlist for the external-display tablet fix.
 *
 * The standalone Zygisk module is global, but only acts on packages selected here. The channel is
 * a per-package boolean system property `persist.zui.extdensity.<fnv1a(pkg)>` = 1, which the module
 * reads directly from the target app process - no LSPosed, no companion, no cross-app file access.
 * The choice itself is kept in this app's prefs so the picker can show it and re-assert the props;
 * the props are what the module actually reads. Selecting an app applies on its next start, no
 * reboot.
 *
 * [propName] MUST stay byte-for-byte identical to `propName()` in the module's native module.cpp.
 */
object ExtDensityTargets {

    private const val PREFS = "ext_density_targets"
    private const val KEY = "packages"
    private const val PROP_PREFIX = "persist.zui.extdensity."

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    /** FNV-1a 32-bit over the UTF-8 bytes, printed as 8 lowercase hex - matches the module. */
    fun propName(pkg: String): String {
        var h = 2166136261L
        for (b in pkg.toByteArray(Charsets.UTF_8)) {
            h = (h xor (b.toLong() and 0xffL)) and 0xffffffffL
            h = (h * 16777619L) and 0xffffffffL
        }
        return PROP_PREFIX + "%08x".format(h)
    }

    /**
     * Toggle one package: mirror it to the module's property via root, and only on success record
     * the choice locally, so the stored set never claims more than the module actually sees.
     * Returns whether the root write succeeded (false usually means root was refused).
     * Deselect writes "0" rather than deleting: `resetprop --delete` does not reliably drop a
     * persist prop at runtime, so the module keys on the value (only "1" means selected).
     */
    fun setSelected(context: Context, pkg: String, on: Boolean): Boolean {
        val ok = Root.exec("resetprop ${propName(pkg)} ${if (on) "1" else "0"}").ok
        if (ok) {
            val cur = read(context).toMutableSet()
            if (on) cur.add(pkg) else cur.remove(pkg)
            prefs(context).edit().putStringSet(KEY, HashSet(cur)).apply()
        }
        return ok
    }

    /** Re-assert every stored selection into props (they can be cleared out of band). */
    fun syncAll(context: Context) {
        val sel = read(context)
        if (sel.isEmpty()) return
        Root.exec(sel.joinToString("\n") { "resetprop ${propName(it)} 1" })
    }
}
