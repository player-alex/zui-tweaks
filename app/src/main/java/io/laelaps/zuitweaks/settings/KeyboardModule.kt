package io.laelaps.zuitweaks.settings

import android.content.Context
import java.io.File

/**
 * Installs and reports on the Magisk key-layout module, from inside the app.
 *
 * The zip is carried in the APK's assets, built by the `packMagiskModule` Gradle task. It
 * contains no key layouts at all - `customize.sh` derives them from whichever ROM it is
 * flashed onto - so it is a few kilobytes of text and there is no reason not to ship it.
 *
 * [status] deliberately asks the same questions `verify-keylayout.sh` asks, and in the
 * same order, because on 2026-08-24 the module spent a whole boot **installed, enabled,
 * byte-correct and unmounted**. Everything reachable without root - and everything the
 * Magisk app's own module list shows - looked perfect. The two facts that distinguish a
 * working module from that one are Magisk's boot log and the layouts actually in
 * /system, so the app reads both rather than inferring from the module's presence.
 */
object KeyboardModule {

    const val ID = "hwkeyboard_langswitch"
    private const val ASSET = "hwkeyboard_langswitch.zip"
    private const val DIR = "/data/adb/modules/$ID"

    enum class State {
        /** No root shell, so nothing here can be read or done. */
        NO_ROOT,

        /** Rooted, but not by Magisk - this module has nowhere to go. */
        NO_MAGISK,

        NOT_INSTALLED,

        /** Flashed, sitting in modules_update. Magisk moves it into place at the next boot. */
        PENDING_REBOOT,

        /**
         * On disk under /data/adb/modules and Magisk did not load it at this boot, so
         * nothing is mounted and the fix is not in effect. Reboot.
         */
        NOT_LOADED,

        /** Loaded, but some layout still maps scancode 100 to ALT_RIGHT. Re-flash. */
        INCOMPLETE,

        ACTIVE,
    }

    class Status(
        val state: State,
        /** Layouts still mapping scancode 100 to ALT_RIGHT. Zero is the working state. */
        val altRightLayouts: Int = 0,
        /** Layouts the installed module carries. */
        val patchedLayouts: Int = 0,
        /** `disable`, `remove` or `skip_mount` found in the module directory. */
        val markers: List<String> = emptyList(),
        val magiskVersion: String = "",
    )

    /**
     * One root round trip, printing `key=value` lines.
     *
     * Written without a single *shell* `$`, which is why it reads slightly oddly -
     * `id -u | sed` rather than `echo "root=$(id -u)"`. In a Kotlin raw string every one
     * of those would need `${'$'}`, and the escaping noise buries what the script does.
     * The `$ID` and `$DIR` here are Kotlin interpolations, resolved before the shell
     * ever sees the text.
     */
    private val PROBE = """
        id -u | sed 's/^/root=/'
        magisk -V 2>/dev/null | sed 's/^/magisk=/'
        [ -d $DIR ] && echo installed=1 || echo installed=0
        [ -d /data/adb/modules_update/$ID ] && echo staged=1 || echo staged=0
        grep -q '$ID: loading module files' /cache/magisk.log 2>/dev/null && echo loaded=1 || echo loaded=0
        [ -e $DIR/disable ] && echo marker=disable
        [ -e $DIR/remove ] && echo marker=remove
        [ -e $DIR/skip_mount ] && echo marker=skip_mount
        grep -l '^key[[:space:]]\{1,\}100[[:space:]]\{1,\}ALT_RIGHT' /system/usr/keylayout/*.kl 2>/dev/null | wc -l | sed 's/^/altright=/'
        ls $DIR/system/usr/keylayout/ 2>/dev/null | wc -l | sed 's/^/patched=/'
        echo done=1
    """.trimIndent()

    /** Blocking. Call it off the main thread. */
    fun status(): Status {
        val result = Root.exec(PROBE, timeoutSeconds = 20)
        if (!result.ok) return Status(State.NO_ROOT)

        val fields = HashMap<String, String>()
        val markers = ArrayList<String>()
        for (line in result.output.lineSequence()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val key = line.substring(0, i).trim()
            val value = line.substring(i + 1).trim()
            if (key == "marker") markers += value else fields[key] = value
        }

        if (fields["root"] != "0") return Status(State.NO_ROOT)
        val magisk = fields["magisk"].orEmpty()
        if (magisk.isEmpty()) return Status(State.NO_MAGISK)

        val altRight = fields["altright"]?.toIntOrNull() ?: 0
        val patched = fields["patched"]?.toIntOrNull() ?: 0

        val state = when {
            fields["staged"] == "1" -> State.PENDING_REBOOT
            fields["installed"] != "1" -> State.NOT_INSTALLED
            fields["loaded"] != "1" -> State.NOT_LOADED
            altRight > 0 -> State.INCOMPLETE
            else -> State.ACTIVE
        }
        return Status(state, altRight, patched, markers, magisk)
    }

    /**
     * Flashes the bundled zip. Blocking; the module applies at the next boot either way,
     * because Magisk mounts modules at boot and only at boot.
     */
    fun install(context: Context): Root.Result {
        val zip = File(context.cacheDir, ASSET)
        try {
            context.assets.open(ASSET).use { input ->
                zip.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            return Root.Result(-3, "could not unpack $ASSET from the APK: $e")
        }
        // Magisk runs as root and reads it straight from the app's cache directory.
        return Root.exec("magisk --install-module ${zip.absolutePath}", timeoutSeconds = 120)
    }

    /**
     * Clears a stale `disable`/`remove`/`skip_mount` without re-flashing.
     *
     * Worth its own action because of how the 2026-08-24 fault actually arose: the module
     * was switched off, the device rebooted for an unrelated reason, and it was switched
     * back on afterwards. Enabling a module in the Magisk app only writes a flag - the
     * next boot is what mounts it - so it read as enabled and did nothing for nine hours.
     */
    fun clearMarkers(): Root.Result =
        Root.exec("rm -f $DIR/disable $DIR/remove $DIR/skip_mount && echo cleared")

    fun reboot(): Root.Result = Root.exec("svc power reboot || reboot", timeoutSeconds = 10)
}
