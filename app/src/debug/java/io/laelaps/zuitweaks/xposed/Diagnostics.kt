package io.laelaps.zuitweaks.xposed

/**
 * The seam that keeps diagnostic-only hooks out of a release build.
 *
 * There are two of this file, one per build type, with the same fully qualified name.
 * `HookEntry` calls through it and never names a diagnostic class directly, so the release
 * variant does not merely leave the code unreachable - it never compiles it in. That
 * matters here because `buildTypes.release` has optimization disabled, so there is no R8
 * pass to shake an unreferenced class out afterwards.
 *
 * This is the debug half: it does the work.
 */
object Diagnostics {

    /**
     * Forces Launcher3's stash guards open. Kept because the reconnaissance behind it is
     * worth keeping - `supportsVisualStashing()` and `isInApp()` survived R8 with their
     * names, so they can be forced directly - but the stash path does not actually work on
     * this build: the flags flip and `isStashed()` never becomes true (RECON 3/I).
     * A switch that turns on and does nothing has no business in a shipped build.
     */
    fun installStash(classLoader: ClassLoader, config: Flags.Config) =
        StashHook.install(classLoader, config)
}
