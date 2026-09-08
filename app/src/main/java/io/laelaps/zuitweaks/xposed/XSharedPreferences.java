package io.laelaps.zuitweaks.xposed;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Map;

/**
 * ≈ de.robv.android.xposed.XSharedPreferences, backed by libXposed's remote preferences.
 *
 * The legacy {@code .file} reachability API is emulated, and the two questions it answers are
 * kept distinct because the callers act on the difference:
 *
 *   canRead() - the remote preferences object resolved, i.e. the bridge reaches this process.
 *   exists()  - and it has content. The settings UI writes a {@code _schema} key on its very
 *               first run (SettingsStore.materialise), so a non-empty store is the signal that
 *               the preferences have actually been created.
 *
 * Reporting both as one value made "the bridge does not reach this process" and "the settings
 * UI has never run" indistinguishable, which is exactly the pair trap 6.9 is about.
 */
public final class XSharedPreferences {

    private final SharedPreferences sp;
    public final FileShim file;

    public XSharedPreferences(String appId, String name) {
        SharedPreferences s = null;
        try {
            if (XpModule.module != null) s = XpModule.module.getRemotePreferences(name);
        } catch (Throwable ignored) {
        }
        sp = s;
        file = new FileShim(sp, name);
    }

    public void reload() {}

    public void makeWorldReadable() {}

    public String getString(String key, String def) {
        try {
            return sp != null ? sp.getString(key, def) : def;
        } catch (Throwable t) {
            return def;
        }
    }

    public Map<String, ?> getAll() {
        return sp != null ? sp.getAll() : Collections.emptyMap();
    }

    public static final class FileShim {
        private final SharedPreferences sp;
        private final String label;

        FileShim(SharedPreferences sp, String label) {
            this.sp = sp;
            this.label = label;
        }

        /** The remote preferences resolved - the LSPosed bridge reaches this process. */
        public boolean canRead() {
            return sp != null;
        }

        /** Readable AND non-empty, i.e. the settings UI has written the store at least once. */
        public boolean exists() {
            if (sp == null) return false;
            try {
                Map<String, ?> all = sp.getAll();
                return all != null && !all.isEmpty();
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public String toString() {
            return "remote-prefs:" + label;
        }
    }
}
