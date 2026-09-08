package io.laelaps.zuitweaks.xposed

/**
 * Release half of the diagnostics seam - see the debug copy of this file for what it is
 * and why there are two.
 *
 * Every function here is a no-op, and the classes they would have called are not in this
 * variant's source set at all.
 */
object Diagnostics {

    /** No-op. `StashHook` is a debug-only class; the stash keys do nothing in a release build. */
    @Suppress("UNUSED_PARAMETER")
    fun installStash(classLoader: ClassLoader, config: Flags.Config) = Unit
}
