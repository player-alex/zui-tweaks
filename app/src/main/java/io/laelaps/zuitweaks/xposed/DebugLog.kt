package io.laelaps.zuitweaks.xposed

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Optional file sink for [Logx], for bug reports from devices that are not on a cable.
 *
 * logcat is a ring buffer: on a busy launcher the hook-install lines are gone within a couple of
 * minutes, which is exactly the window in which someone notices a bug and reaches for `adb logcat`.
 * LSPosed's own file log survives that, but it is under `/data/adb` and needs root to read. This
 * writes the same lines somewhere a bug reporter can actually get at.
 *
 * **Truncated once per process, then appended.** Each hooked process starts a fresh file when it
 * starts - which after a reboot means the file holds this boot - so a report is what happened since
 * the launcher last started, not a log that grows without bound. One file per process, because
 * three processes truncating one shared file would leave whichever started last.
 *
 * Off by default. It is a `Toggle` rather than a debug override because the person who needs it is
 * the one filing the bug report, and telling them to write a file into `/sdcard/zuitweaks.conf`
 * first defeats the point.
 */
object DebugLog {

    /** The launcher holds MANAGE_EXTERNAL_STORAGE, so this is reachable and easy to describe. */
    private const val PREFERRED_DIR = "/sdcard/zuitweaks"

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var sink: File? = null

    /**
     * Opens the file for this process. Safe to call more than once; only the first call truncates.
     *
     * [context] is used only for the fallback path. Not every hooked process can write to
     * `/sdcard` - the IME holds no storage permission at all - so a process that cannot gets its
     * own external files dir, which needs no permission. Silence would be the wrong outcome for a
     * diagnostic that someone has deliberately switched on.
     */
    @Synchronized
    fun open(context: Context?, procTag: String) {
        // Guarded on the sink, not on "have I been called": the first call happens in
        // handleLoadPackage where there is no Context yet, and a process that needs the
        // fallback path has to be able to succeed on a later call. Once open it never
        // truncates again, so those later calls cost nothing.
        if (sink != null) return
        val candidates = listOfNotNull(
            File(PREFERRED_DIR),
            runCatching { context?.getExternalFilesDir(null) }.getOrNull(),
        )
        for (dir in candidates) {
            val f = runCatching {
                dir.mkdirs()
                File(dir, "zuitweaks-$procTag.log").apply {
                    // Truncate: this process is starting, and the previous run's log is not what a
                    // report about this run should contain.
                    writeText("=== ZuiTweaks debug log - $procTag - opened ${Date()} ===\n")
                }
            }.getOrNull()
            if (f != null && f.canWrite()) {
                sink = f
                Logx.i("debug log -> ${f.absolutePath}")
                return
            }
        }
        Logx.i("debug log: no writable location (tried ${candidates.joinToString { it.path }})")
    }

    /** True once a file is open, so [Logx] can skip the formatting work when it is not. */
    val active: Boolean get() = sink != null

    fun write(level: Char, msg: String) {
        val f = sink ?: return
        // Never let logging break a hook: a full disk or a revoked permission must not throw out
        // of Logx and into the launcher's call stack.
        runCatching { f.appendText("${stamp.format(Date())} $level $msg\n") }
    }

    fun write(level: Char, msg: String, t: Throwable) {
        write(level, msg)
        runCatching { sink?.appendText(t.stackTraceToString()) }
    }
}
