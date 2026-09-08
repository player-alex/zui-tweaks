package io.laelaps.zuitweaks.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * LSPosed entry point — modern libXposed (API 101). Registered by
 * META-INF/xposed/java_init.list; scope is fixed in META-INF/xposed/scope.list.
 *
 * Scope is com.zui.launcher, not com.android.systemui: on this device the external-display
 * bottom bar is the launcher's "Taskbar_dp" window (RECON.md 3). A crash here costs the home
 * screen, not the boot — but nothing below may throw.
 *
 * The external-display tablet fix does NOT live here: it is a standalone Zygisk module
 * (zygisk-extdensity), targeting only the apps the user picks in this app's App Selection
 * screen. Keeping it out of LSPosed avoids double-hooking the same process from two frameworks.
 */
class HookEntry : XposedModule() {

    private var procName: String = ""

    init {
        // Store the module instance early so the compat shim (XpModule) can use hook()/log()
        // once onPackageLoaded runs (the framework has attached the interface by then).
        XpModule.module = this
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XpModule.module = this
        procName = param.processName
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        val classLoader = param.defaultClassLoader
        val pkg = param.packageName
        try {
            if (Flags.isDisabled()) {
                Logx.i("kill switch present - no hooks installed")
                return
            }
            val config = Flags.readConfig()
            // Before the first hook line, so the file holds the whole of this process's life.
            // No Context here; DebugLog retries from Application.onCreate for the processes
            // that cannot write to /sdcard directly.
            if (config.bool("debugLog", false)) DebugLog.open(null, pkg.substringAfterLast('.'))
            // Which config channels this process can see. There is no Context yet, so
            // Settings.Global is reported as no-context here; the IME path re-reports it
            // from onCreate. Read this line before believing a setting had no effect.
            Logx.i("config sources in $pkg: ${Flags.configSourceReport(null)}")

            if (pkg == Flags.TOUCHPAD_PKG) {
                Logx.i("loaded into $pkg (virtual touchpad); config[$config]")
                if (config.bool("virtualCursor", true)) {
                    VirtualCursorHook.install(classLoader, config)
                }
                if (config.bool("touchpadPortrait", true)) {
                    TouchpadOrientationHook.install(classLoader, config)
                }
                return
            }
            // Any other scoped package is treated as an IME candidate: the probe is a no-op
            // unless the process actually has an InputMethodService. install() hooks onCreate
            // and re-reads config there with a Context.
            if (pkg != Flags.TARGET_PKG) {
                ImeSwitchProbe.install(classLoader, pkg, config)
                return
            }
            Logx.i("loaded into $pkg (process=$procName); config[$config]")

            if (config.bool("stripInsets", true)) InsetsPatcher.install(classLoader)
            if (config.bool("secondaryHomeGuard", true)) SecondaryHomeGuard.install(classLoader)
            TransientTaskbarHook.install(classLoader, config)
            // Debug-only hooks go through the Diagnostics seam: there is one of that object
            // per build type (debug does the work, release is a no-op), so the release
            // variant never compiles the diagnostic classes in at all.
            Diagnostics.installStash(classLoader, config)
            IconQualityHook.install(classLoader, config)
            if (config.bool("control", true)) {
                Control.requestRegistration()
                // Registers on Application.onCreate, so the receiver exists even when neither
                // the external taskbar nor the drawer has appeared this boot.
                Control.installEarlyRegistration(classLoader)
            }
            if (config.bool("probe", false)) ProbeHook.install(classLoader)
            // Drawer-grouping recon: observation only, off by default. Enable with
            // `longpressprobe=1` in /sdcard/zuitweaks.conf, then force-stop the launcher.
            if (config.bool("longpressProbe", false)) LongPressProbeHook.install(classLoader)
            // Drawer grouping: long-press moves an icon instead of opening the menu, and a
            // drop onto another icon makes a folder. Configured in the app (Launcher section).
            if (config.bool("drawerMovable", true)) {
                val folderUi = config.bool("folderUi", true)
                DrawerLongPressHook.install(
                    classLoader, folderUi,
                    config.int("drawerDragSlop", 40),
                    config.int("folderDragSlop", 30),
                    config.int("groupZonePct", 36),
                )
                if (folderUi) DrawerFolderRender.install(
                    classLoader,
                    config.bool("folderIconProbe", false),
                    config.bool("drawerOrder", true),
                    config.int("folderPreviewGrid", 2),
                )
            }
        } catch (t: Throwable) {
            Logx.e("onPackageLoaded failed", t)
        }
    }
}
