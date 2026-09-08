package io.laelaps.zuitweaks.xposed;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * Holds the live libXposed module instance (which IS the XposedInterface, via
 * XposedInterfaceWrapper). Set from {@link HookEntry}; used by the compat shim to reach
 * hook()/log()/getRemotePreferences().
 *
 * Part of the legacy-Xposed-API compat shim written in Java on purpose: Java members are
 * platform types in Kotlin, so the migrated hook bodies keep the null-leniency the original
 * de.robv API had.
 */
public final class XpModule {
    public static volatile XposedModule module;

    private XpModule() {}

    static void log(String msg) {
        XposedModule m = module;
        if (m != null) {
            try {
                m.log(Log.INFO, "ZuiTweaks", msg);
            } catch (Throwable ignored) {
            }
        }
    }
}
