package io.laelaps.zuitweaks.settings

import java.util.concurrent.TimeUnit

/**
 * Runs a shell script as root, from inside the app.
 *
 * This exists so that nothing about using this module needs a terminal. The device is
 * already rooted - Magisk and LSPosed are hard prerequisites - so the permissions were
 * always there; the app simply never asked for them, and printed `adb shell ...` lines
 * for a human to run instead.
 *
 * Two things it can do that the app cannot do any other way:
 *
 *  - restart a hooked process. An Xposed hook is injected only when a process starts, so
 *    most settings are pending until then. `FORCE_STOP_PACKAGES` is `signature|privileged`
 *    and out of reach, and `com.zui.wifip2p` runs as the system UID, where even a granted
 *    force-stop does nothing - it has to be `kill -9`.
 *  - install the Magisk key-layout module and read back whether Magisk actually mounted it.
 *
 * Note on the quoting trap in SESSION.md: `adb shell su -c 'a && b'` fails because the
 * device's own shell, running as the unprivileged `shell` user, parses `&&` before `su`
 * ever sees it. That trap does not apply here. This spawns `su` directly and writes the
 * script to its stdin, so there is no intermediate shell and no re-parsing - multi-line
 * scripts are safe.
 */
object Root {

    class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    private val TIMED_OUT = Result(-1, "timed out")
    private val NO_ROOT = Result(-2, "su not available")

    /**
     * Cached answer to "can a root shell be obtained", so the Magisk prompt appears once
     * rather than on every status refresh.
     */
    @Volatile
    private var cachedAvailable: Boolean? = null

    /** Forget the cached answer, so a denied prompt can be retried. */
    fun forget() {
        cachedAvailable = null
    }

    /**
     * Runs [script] in a root shell and returns its combined output.
     *
     * stderr is merged into stdout deliberately. Draining two pipes from one thread
     * deadlocks as soon as either fills, and a root helper that can hang on a full pipe is
     * worse than one that loses the stream separation.
     *
     * Blocking. Call it off the main thread.
     */
    fun exec(script: String, timeoutSeconds: Long = 60): Result {
        val process = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (_: Exception) {
            return NO_ROOT
        }

        return try {
            process.outputStream.bufferedWriter().use { w ->
                w.write(script)
                w.write("\nexit\n")
            }

            // The read has to happen on another thread: it runs until EOF, and a script that
            // never exits would otherwise hang the caller past any timeout.
            //
            // It accumulates chunk by chunk rather than assigning one readText() at the end,
            // because EOF is not reached when `su` exits - it is reached when the last holder
            // of the write end of the pipe closes it, and a grandchild that outlives the
            // shell (anything backgrounded, `am` handing off, a killed process's reaper) keeps
            // it open. The join below then times out with `output` never assigned, and the
            // caller sees an empty string and exit code 0 - which reads as "the probe found
            // nothing", not as "the output was lost". Everything read up to that point is
            // returned instead.
            val collected = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            if (n > 0) synchronized(collected) { collected.append(String(buf, 0, n)) }
                        }
                    }
                }
            }
            reader.isDaemon = true
            reader.start()

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return TIMED_OUT
            }
            reader.join(TimeUnit.SECONDS.toMillis(2))
            val output = synchronized(collected) { collected.toString() }
            Result(process.exitValue(), output.trim())
        } catch (_: Exception) {
            process.destroyForcibly()
            NO_ROOT
        }
    }
}
