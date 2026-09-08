package io.laelaps.zuitweaks.settings

import android.content.Context
import java.io.File

/**
 * Installs and reports on the external-display **Zygisk** module from inside the app - the same
 * self-install idea as [KeyboardModule], for the standalone `zygisk-extdensity` module.
 *
 * The zip is carried in the APK assets (built by the `packExtDensityModule` Gradle task from a
 * snapshot in `magisk/zygisk_extdensity/`). Being a Zygisk module it needs one reboot to first take
 * effect; after that, *which* apps it touches is chosen on the App Selection screen with no reboot.
 *
 * Status is simpler than the keyboard module's: a Zygisk module has no per-file mount to verify, so
 * "installed and not disabled, not still staged" is taken to mean loaded (Zygisk loads it at boot).
 */
object ZygiskModule {

    const val ID = "zygisk_extdensity"
    private const val ASSET = "zygisk_extdensity.zip"
    private const val DIR = "/data/adb/modules/$ID"

    enum class State { NO_ROOT, NO_MAGISK, NOT_INSTALLED, PENDING_REBOOT, DISABLED, ACTIVE }

    class Status(val state: State, val magiskVersion: String = "")

    private val PROBE = """
        id -u | sed 's/^/root=/'
        magisk -V 2>/dev/null | sed 's/^/magisk=/'
        [ -d $DIR ] && echo installed=1 || echo installed=0
        [ -d /data/adb/modules_update/$ID ] && echo staged=1 || echo staged=0
        [ -e $DIR/disable ] && echo disabled=1 || echo disabled=0
        echo done=1
    """.trimIndent()

    /** Blocking. Call it off the main thread. */
    fun status(): Status {
        val result = Root.exec(PROBE, timeoutSeconds = 20)
        if (!result.ok) return Status(State.NO_ROOT)

        val f = HashMap<String, String>()
        for (line in result.output.lineSequence()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            f[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        if (f["root"] != "0") return Status(State.NO_ROOT)
        val magisk = f["magisk"].orEmpty()
        if (magisk.isEmpty()) return Status(State.NO_MAGISK)

        val state = when {
            f["staged"] == "1" -> State.PENDING_REBOOT
            f["installed"] != "1" -> State.NOT_INSTALLED
            f["disabled"] == "1" -> State.DISABLED
            else -> State.ACTIVE
        }
        return Status(state, magisk)
    }

    /** Flashes the bundled zip. Blocking; it applies at the next boot (Zygisk loads at boot). */
    fun install(context: Context): Root.Result {
        val zip = File(context.cacheDir, ASSET)
        try {
            context.assets.open(ASSET).use { input ->
                zip.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            return Root.Result(-3, "could not unpack $ASSET from the APK: $e")
        }
        return Root.exec("magisk --install-module ${zip.absolutePath}", timeoutSeconds = 120)
    }

    /** Clears a `disable` marker without re-flashing (takes effect at the next boot). */
    fun enable(): Root.Result = Root.exec("rm -f $DIR/disable && echo enabled")

    fun reboot(): Root.Result = Root.exec("svc power reboot || reboot", timeoutSeconds = 10)
}
