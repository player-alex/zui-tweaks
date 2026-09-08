package io.laelaps.zuitweaks.xposed

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import java.lang.reflect.Member

/**
 * Makes the right-Alt language-switch key behave the way it does on Windows: a letter
 * typed before the key is fully released still arrives, and it arrives in the language
 * that was just switched TO.
 *
 * On a Korean layout 한/영 is physically right Alt. Two separate faults stack up:
 *
 *  1. Android keeps right-Alt as a live modifier, so the letter reaches the IME as
 *     Alt+letter and is discarded. Measured: `onKeyDown keyCode=32 meta=0x22
 *     consumed=true` with no output at all. Clearing the two ALT bits fixes this.
 *
 *  2. Even once it produces output, the key is handled *before*
 *     onCurrentInputMethodSubtypeChanged lands, so it is typed in the OLD language -
 *     pressing 한/영 then "이" yields "dㅣ". So the key also has to wait for the switch.
 *
 * Hence: while right-Alt is held, matching key events are consumed and queued, then
 * replayed in order once the subtype change arrives (or after a timeout, so nothing can
 * ever be swallowed permanently). Replay goes through invokeOriginalMethod, bypassing
 * this hook.
 *
 * SUPERSEDED, and off by default. The root cause is that scancode 100 is mapped to
 * ALT_RIGHT at all; remapping it to LANGUAGE_SWITCH in a device key layout removes the
 * modifier and both faults with it, without any hook (see magisk/hwkeyboard_langswitch and the
 * README). This is kept as a fallback for a keyboard whose layout cannot be remapped -
 * note it can only ever fix fault 1, never fault 2, because the second switch is decided
 * in system_server before the IME sees anything.
 *
 * Scoped to the RIGHT Alt only: left-Alt shortcuts and any chord carrying Ctrl/Meta/Sym
 * are left untouched, and the Alt key itself is never rewritten - rewriting it would stop
 * the language switch from happening at all.
 */
object AltGraveKeyFix {

    private const val ALT_BITS = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON

    /** Chords that are still meant to be chords even with right-Alt held. */
    private const val REAL_MODIFIERS =
        KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON or KeyEvent.META_SYM_ON

    /**
     * Keys that must reach the system exactly as they are, metaState included.
     *
     * The language switch on this setup is Alt+Space (measured: SPACE arriving with
     * meta=0x22, and 0xa3 when Shift is also down). Queueing that SPACE consumed the
     * chord and the switch stopped happening; even just clearing its ALT bits breaks the
     * chord, since the modifier is what makes it one. Alt+Tab is excluded for the same
     * reason. The modifier keys themselves are never rewritten either.
     */
    private val CHORD_KEYS = setOf(
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_ALT_LEFT,
        KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_META_LEFT,
        KeyEvent.KEYCODE_META_RIGHT,
    )

    private class Pending(val method: Member, val keyCode: Int, val event: KeyEvent)

    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<Pending>()

    /**
     * Key codes whose DOWN we queued. A key whose DOWN already went through must have its
     * UP delivered immediately - holding the UP back leaves the IME thinking the key is
     * still down, which shows up as repeats.
     */
    private val heldDown = HashSet<Int>()
    private var service: Any? = null
    private var replaying = false

    private var defer = true
    private var timeoutMs = 400L
    private var replayDelayMs = 0L
    private var logged = 0

    private val timeoutFlush = Runnable { flush("timeout") }

    fun install(serviceClass: Class<*>, config: Flags.Config) {
        defer = config.bool("altDefer", true)
        timeoutMs = config.long("altDeferTimeoutMs", 400L)
        replayDelayMs = config.long("altReplayDelayMs", 0L)

        for (name in listOf("onKeyDown", "onKeyUp")) {
            val declaring = declaringOf(serviceClass, name) ?: run {
                Logx.e("alt fix: no $name(int, KeyEvent) on ${serviceClass.name}")
                continue
            }
            try {
                XposedHelpers.findAndHookMethod(
                    declaring, name, Int::class.javaPrimitiveType, KeyEvent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            Logx.guard("alt fix $name") { handle(param, name) }
                        }
                    },
                )
                Logx.i("alt fix: hooked ${declaring.simpleName}.$name (defer=$defer timeout=${timeoutMs}ms)")
            } catch (t: Throwable) {
                Logx.e("alt fix: could not hook $name", t)
            }
        }
    }

    /** Called from the subtype-change hook: the switch has landed, let the keys through. */
    fun onSubtypeChanged() {
        if (pending.isEmpty()) return
        // Post rather than replay inline so the IME finishes applying the new subtype
        // first; this callback runs before its own work completes.
        main.postDelayed({ Logx.guard("alt flush") { flush("subtype") } }, replayDelayMs)
    }

    private fun handle(param: XC_MethodHook.MethodHookParam, name: String) {
        if (replaying) return

        val event = param.args[1] as? KeyEvent ?: return
        val meta = event.metaState

        if (meta and KeyEvent.META_ALT_RIGHT_ON == 0) return
        if (meta and REAL_MODIFIERS != 0) return
        if (event.keyCode in CHORD_KEYS) return

        service = param.thisObject
        val cleaned = KeyEvent(
            event.downTime, event.eventTime, event.action, event.keyCode, event.repeatCount,
            meta and ALT_BITS.inv(), event.deviceId, event.scanCode, event.flags, event.source,
        )

        val isDown = event.action == KeyEvent.ACTION_DOWN
        // An UP for a key we did not queue belongs to a press that already completed;
        // deliver it now, only with the stale ALT bits removed.
        if (!defer || (!isDown && !heldDown.contains(event.keyCode))) {
            param.args[1] = cleaned
            log("$name keyCode=${event.keyCode} meta 0x${meta.toString(16)} -> 0x${cleaned.metaState.toString(16)} (passed through)")
            return
        }

        if (isDown) heldDown += event.keyCode else heldDown -= event.keyCode
        pending += Pending(param.method, event.keyCode, cleaned)
        param.result = true // consumed; it will be replayed once the switch lands
        main.removeCallbacks(timeoutFlush)
        main.postDelayed(timeoutFlush, timeoutMs)
        log("$name keyCode=${event.keyCode} queued (${pending.size} pending)")
    }

    private fun flush(reason: String) {
        main.removeCallbacks(timeoutFlush)
        if (pending.isEmpty()) return
        val target = service
        val queued = ArrayList(pending)
        pending.clear()
        heldDown.clear()
        if (target == null) {
            Logx.e("alt fix: nothing to replay onto; ${queued.size} events dropped")
            return
        }
        log("replaying ${queued.size} events ($reason)")
        replaying = true
        try {
            for (p in queued) {
                try {
                    XposedBridge.invokeOriginalMethod(p.method, target, arrayOf(p.keyCode, p.event))
                } catch (t: Throwable) {
                    Logx.e("alt fix: replay of keyCode=${p.keyCode} failed", t)
                }
            }
        } finally {
            replaying = false
        }
    }

    private fun log(message: String) {
        if (logged < 20) {
            logged++
            Logx.i("alt fix: $message")
        }
    }

    private fun declaringOf(start: Class<*>, name: String): Class<*>? {
        var c: Class<*>? = start
        while (c != null) {
            if (c.declaredMethods.any {
                    it.name == name && it.parameterTypes.size == 2 &&
                        it.parameterTypes[1] == KeyEvent::class.java
                }
            ) return c
            c = c.superclass
        }
        return null
    }
}
