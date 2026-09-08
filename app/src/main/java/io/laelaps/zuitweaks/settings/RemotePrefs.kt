package io.laelaps.zuitweaks.settings

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * App-side bridge to the module's remote preferences over the **libXposed service**.
 *
 * Replaces the legacy `getSharedPreferences(MODE_WORLD_READABLE)` + `xposedsharedprefs`
 * manifest bridge, which no longer exists under libXposed API 101. When this module is
 * enabled in LSPosed, the framework hands an [XposedService] binder to this (the module's
 * own) app process through the bundled `XposedProvider`; writes to
 * `service.getRemotePreferences(name)` here are delivered to the hooked processes, which read
 * them via `XposedModule.getRemotePreferences(name)` (see the XSharedPreferences shim + Flags).
 *
 * The same `name` ("settings") is used on both ends — see [SettingsStore.NAME] / Flags.PREFS_NAME.
 */
object RemotePrefs {

    @Volatile
    private var service: XposedService? = null
    private val bound = CountDownLatch(1)

    init {
        // Registering replays immediately if the binder was already delivered.
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) {
                service = s
                bound.countDown()
            }

            override fun onServiceDied(s: XposedService) {
                service = null
            }
        })
    }

    /**
     * The module's remote SharedPreferences, or null when this module is not active in LSPosed
     * (the binder never arrives). Blocks up to [timeoutMs] on first call so a freshly-launched
     * app does not race the binder; returns immediately once bound.
     */
    fun open(name: String, timeoutMs: Long = 1500): SharedPreferences? {
        var svc = service
        if (svc == null) {
            try {
                bound.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
            svc = service
        }
        return svc?.let { runCatching { it.getRemotePreferences(name) }.getOrNull() }
    }

    /** True once the libXposed service has bound — i.e. the module is active. */
    val isActive: Boolean get() = service != null
}
