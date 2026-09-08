package io.laelaps.zuitweaks.settings

import android.content.Context

/**
 * Turns ZUI's freeform sidebar off and back on, so LSPosed's dialogs can be tapped.
 *
 * Enabling a module in LSPosed, or changing its scope, opens a dialog that Android treats as
 * security-sensitive: if any app is drawing an overlay window on top, the system refuses to
 * deliver the touch at all and LSPosed shows "앱이 인터페이스를 가리고 있어…". ZUI's freeform
 * sidebar (`com.zui.freeform.sidebar`) is exactly such an overlay and is on by default, so the
 * dialog is unusable until it is turned off. That is a ZUI/Android interaction, not an LSPosed
 * fault, and nothing in this app can consent to that dialog on the user's behalf - the only
 * thing that can be automated is getting the overlay out of the way.
 *
 * Revoking the app op does **not** work: `com.zui.freeform.sidebar` runs as the system UID, where
 * `appops set SYSTEM_ALERT_WINDOW ignore` is ignored. The two `Settings.System` keys below are
 * what actually stop it, which is why this writes settings rather than app ops.
 *
 * The previous values are captured before they are cleared and restored from that capture, not
 * from a hardcoded `1`: leaving the user's sidebar permanently off - or switching it *on* for
 * someone who had it off already - would be this app breaking something it was asked to
 * un-break for a moment.
 *
 * **Reporting is not the same question as acting.** Writing the keys plus a force-stop does remove
 * the overlay, but ZUI puts `enable_temp_zuifreeformbar` straight back to `1` afterwards - measured
 * on the device: one successful suppress left the keys reading `0` and `1` while the window manager
 * showed the sidebar owning no window at all. Believing that key made the card report "still on"
 * about a sidebar that was already gone, so the button looked like it had done nothing and invited
 * a second press. [status] therefore asks what is on screen; the transient key is written and
 * restored, because that is part of making it go away, but it is never read back.
 */
object FreeformSidebar {

    const val PKG = "com.zui.freeform.sidebar"
    private val KEYS = listOf("enable_zuifreeformbar", "enable_temp_zuifreeformbar")

    private const val PREFS = "freeform_sidebar"
    private const val CAPTURED = "captured"

    enum class State {
        /** No root shell, so nothing here can be read or changed. */
        NO_ROOT,

        /** The sidebar is on - it will block LSPosed's dialogs. */
        ON,

        /** Off. Either this app turned it off (a capture exists) or the user did. */
        OFF,
    }

    class Status(val state: State, val suppressedByUs: Boolean)

    /** Blocking. Call it off the main thread. */
    fun status(context: Context): Status {
        val script = """
            echo persistent=${'$'}(settings get system ${KEYS[0]})
            echo windows=${'$'}(dumpsys window windows | grep -c $PKG)
        """.trimIndent()
        val result = Root.exec(script, timeoutSeconds = 20)
        if (!result.ok) return Status(State.NO_ROOT, false)

        fun field(name: String) = result.output.lineSequence()
            .firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.trim()

        // What is on screen right now is the question that matters - a window is what blocks the
        // dialog. Measured on the device: after one successful suppress the keys read 0 and 1
        // while the sidebar owned no window at all, because ZUI writes the transient key back.
        val hasWindow = (field("windows")?.toIntOrNull() ?: 0) > 0
        // The persistent key catches "enabled but has not drawn yet". "null" is what settings
        // get prints for a key never written, and the sidebar ships enabled, so an absent value
        // counts as on - claiming "already off" would send the user to a dialog that still
        // cannot be tapped. enable_temp_zuifreeformbar is deliberately not consulted.
        val persistent = field("persistent")
        val on = hasWindow || persistent == null || persistent == "null" || persistent == "1"
        return Status(if (on) State.ON else State.OFF, captured(context) != null)
    }

    /**
     * Captures the current values, clears them, and stops the sidebar so the change takes effect
     * immediately rather than at its next restart.
     */
    fun suppress(context: Context): Root.Result {
        val read = Root.exec(KEYS.joinToString("\n") { "settings get system $it" }, timeoutSeconds = 20)
        if (read.ok) {
            val values = read.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (values.size == KEYS.size) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(CAPTURED, values.joinToString(",")).commit()
            }
        }
        val script = buildString {
            KEYS.forEach { appendLine("settings put system $it 0") }
            appendLine("am force-stop $PKG")
            appendLine("echo suppressed")
        }
        return Root.exec(script, timeoutSeconds = 30)
    }

    /** Puts back whatever [suppress] captured; falls back to ZUI's default of on. */
    fun restore(context: Context): Root.Result {
        val saved = captured(context)?.split(',').orEmpty()
        val script = buildString {
            KEYS.forEachIndexed { i, key ->
                val v = saved.getOrNull(i)?.takeIf { it != "null" && it.isNotEmpty() } ?: "1"
                appendLine("settings put system $key $v")
            }
            appendLine("echo restored")
        }
        val result = Root.exec(script, timeoutSeconds = 30)
        // Only forget the capture once it has actually been put back, so a failed restore can be
        // retried against the real previous values rather than against the fallback.
        if (result.ok) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CAPTURED).commit()
        }
        return result
    }

    private fun captured(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CAPTURED, null)
}
