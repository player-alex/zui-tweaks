package io.laelaps.zuitweaks.xposed

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/**
 * Observation only. Nothing here changes behaviour.
 *
 * Target bug: with a physical keyboard, switching input language and typing immediately
 * loses input. Two symptoms were observed, both inside a short window after the switch:
 *   - the first keystroke produces nothing at all (in BOTH directions, so it is not the
 *     Hangul combiner and not Korean-specific)
 *   - the next few Korean jamo appear but do not combine ("ㅇㅏㄴ녕하세요")
 * Waiting ~500ms before typing avoids both.
 *
 * The one question that decides how this can be fixed is whether the swallowed key ever
 * reaches the IME:
 *   - `onKeyDown` fires for it  -> the IME got it and its output went to a dead
 *     InputConnection. Buffering keys inside the IME process fixes it.
 *   - `onKeyDown` never fires   -> the framework dropped it while swapping IME sessions,
 *     and a fix would have to hook InputMethodManagerService in system_server.
 *
 * So every line below is timestamped against the switch, and each key logs whether the
 * IME had a live InputConnection at that instant.
 *
 * Hooks resolve the class that actually DECLARES each method, because the IME subclasses
 * InputMethodService and overrides most of them - hooking the base class would silently
 * never fire (the same mistake that cost time on ViewGroup.dispatchHoverEvent).
 */
object ImeSwitchProbe {

    private const val IMS = "android.inputmethodservice.InputMethodService"

    /** uptimeMillis of the last subtype change; -1 before the first switch. */
    private var switchedAt = -1L
    private var connectionClassLogged = false

    private var altFix = false

    fun install(classLoader: ClassLoader, packageName: String, config: Flags.Config) {
        // config here comes from the file only; the IME cannot read /sdcard, so the real
        // decision is deferred to onCreate where a Context makes Settings readable.
        val ims = XposedHelpers.findClassIfExists(IMS, classLoader)
        if (ims == null) {
            Logx.i("ime probe: $packageName has no InputMethodService; skipping")
            return
        }
        // The concrete service class is only known once one exists, and the declaring
        // class of each override has to be resolved from it.
        hookOnCreate(ims, packageName)
    }

    private fun hookOnCreate(ims: Class<*>, packageName: String) {
        try {
            XposedHelpers.findAndHookMethod(ims, "onCreate", object : XC_MethodHook() {
                private var attached = false

                override fun afterHookedMethod(param: MethodHookParam) {
                    Logx.guard("ime probe attach") {
                        if (attached) return@guard
                        attached = true
                        val service = param.thisObject
                        val effective = Flags.readConfig(service as? android.content.Context)
                        altFix = effective.bool("altFix", false)
                        Logx.i("ime probe: attaching to ${service.javaClass.name} in $packageName; config[$effective]")
                        // The fix goes on first so its rewritten KeyEvent is what the
                        // probe below reports - otherwise the log would show the original
                        // metaState and hide whether the fix actually fired.
                        if (altFix) AltGraveKeyFix.install(service.javaClass, effective)
                        if (effective.bool("imeProbe", false)) attachTo(service.javaClass)
                        else hookSubtypeChanged(service.javaClass)
                    }
                }
            })
            Logx.i("ime probe: waiting for an InputMethodService in $packageName")
        } catch (t: Throwable) {
            Logx.e("ime probe: could not hook $IMS.onCreate", t)
        }
    }

    private fun attachTo(serviceClass: Class<*>) {
        hookSubtypeChanged(serviceClass)
        hookOutput(serviceClass.classLoader)
        hookStartInput(serviceClass, "onStartInput")
        hookStartInput(serviceClass, "onStartInputView")
        hookNoArg(serviceClass, "onFinishInput")
        hookNoArg(serviceClass, "onFinishInputView")
        hookKey(serviceClass, "onKeyDown")
        hookKey(serviceClass, "onKeyUp")
    }

    /**
     * What the IME actually emits. Round 1 showed the swallowed keys DO reach onKeyDown
     * with a live connection and are consumed - so the loss is inside the IME, and the
     * proof is that no commit/compose call follows those keys. This also dates the first
     * successful output after a switch, which is the number the buffer length needs.
     */
    private fun hookOutput(classLoader: ClassLoader?) {
        val name = "android.inputmethodservice.RemoteInputConnection"
        val cls = XposedHelpers.findClassIfExists(name, classLoader) ?: run {
            Logx.e("ime probe: $name not found; output cannot be traced"); return
        }
        for ((method, argc) in listOf("commitText" to 2, "setComposingText" to 2, "finishComposingText" to 0)) {
            try {
                val hook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logx.guard("out $method") {
                            val text = param.args.getOrNull(0)?.toString()
                            Logx.i("[ime] ${sinceSwitch()} OUT $method" + if (text != null) "(\"$text\")" else "()")
                        }
                    }
                }
                if (argc == 0) {
                    XposedHelpers.findAndHookMethod(cls, method, hook)
                } else {
                    XposedHelpers.findAndHookMethod(
                        cls, method, CharSequence::class.java, Int::class.javaPrimitiveType, hook
                    )
                }
                Logx.i("ime probe: hooked RemoteInputConnection.$method")
            } catch (t: Throwable) {
                Logx.e("ime probe: could not hook $name.$method", t)
            }
        }
    }

    // ------------------------------------------------------------------ hook helpers

    private fun declaring(start: Class<*>, name: String, argc: Int): Class<*>? {
        var c: Class<*>? = start
        while (c != null) {
            if (c.declaredMethods.any { it.name == name && it.parameterTypes.size == argc }) return c
            c = c.superclass
        }
        return null
    }

    private fun hookSubtypeChanged(serviceClass: Class<*>) {
        val name = "onCurrentInputMethodSubtypeChanged"
        val declaring = declaring(serviceClass, name, 1) ?: run {
            Logx.e("ime probe: no $name found"); return
        }
        try {
            XposedHelpers.findAndHookMethod(
                declaring, name,
                Class.forName("android.view.inputmethod.InputMethodSubtype"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logx.guard(name) {
                            switchedAt = SystemClock.uptimeMillis()
                            Logx.i("[ime] +0ms SUBTYPE CHANGED -> ${describeSubtype(param.args[0])}")
                            if (altFix) AltGraveKeyFix.onSubtypeChanged()
                        }
                    }
                },
            )
            Logx.i("ime probe: hooked ${declaring.simpleName}.$name")
        } catch (t: Throwable) {
            Logx.e("ime probe: could not hook $name", t)
        }
    }

    private fun hookStartInput(serviceClass: Class<*>, name: String) {
        val declaring = declaring(serviceClass, name, 2) ?: run {
            Logx.i("ime probe: no $name(EditorInfo, boolean)"); return
        }
        try {
            XposedHelpers.findAndHookMethod(
                declaring, name, EditorInfo::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logx.guard(name) {
                            Logx.i("[ime] ${sinceSwitch()} $name(restarting=${param.args[1]})")
                        }
                    }
                },
            )
            Logx.i("ime probe: hooked ${declaring.simpleName}.$name")
        } catch (t: Throwable) {
            Logx.e("ime probe: could not hook $name", t)
        }
    }

    private fun hookNoArg(serviceClass: Class<*>, name: String) {
        val declaring = declaring(serviceClass, name, 0) ?: return
        try {
            XposedHelpers.findAndHookMethod(declaring, name, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard(name) { Logx.i("[ime] ${sinceSwitch()} $name()") }
                }
            })
        } catch (t: Throwable) {
            Logx.e("ime probe: could not hook $name", t)
        }
    }

    /**
     * The decisive hook. If a swallowed keystroke shows up here, the IME received it and
     * the loss happened downstream; if it never appears, the framework ate it.
     */
    private fun hookKey(serviceClass: Class<*>, name: String) {
        val declaring = declaring(serviceClass, name, 2) ?: run {
            Logx.e("ime probe: no $name(int, KeyEvent) - keys cannot be traced"); return
        }
        try {
            XposedHelpers.findAndHookMethod(
                declaring, name, Int::class.javaPrimitiveType, KeyEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logx.guard(name) { logKey(name, param, null) }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("$name result") { logKey(name, param, param.result) }
                    }
                },
            )
            Logx.i("ime probe: hooked ${declaring.simpleName}.$name")
        } catch (t: Throwable) {
            Logx.e("ime probe: could not hook $name", t)
        }
    }

    private fun logKey(name: String, param: XC_MethodHook.MethodHookParam, result: Any?) {
        val code = param.args[0] as Int
        val event = param.args[1] as? KeyEvent
        if (result != null) {
            Logx.i("[ime] ${sinceSwitch()} $name keyCode=$code -> consumed=$result")
            return
        }
        val connection = try {
            XposedHelpers.callMethod(param.thisObject, "getCurrentInputConnection")
        } catch (t: Throwable) {
            null
        }
        if (connection != null && !connectionClassLogged) {
            connectionClassLogged = true
            Logx.i("[ime] InputConnection class = ${connection.javaClass.name}")
        }
        Logx.i(
            "[ime] ${sinceSwitch()} $name keyCode=$code scan=${event?.scanCode} " +
                "device=${event?.deviceId} meta=0x${(event?.metaState ?: 0).toString(16)} " +
                "conn=${if (connection == null) "NULL" else "live"} " +
                "dispatchLag=${event?.let { SystemClock.uptimeMillis() - it.eventTime }}ms"
        )
    }

    // ---------------------------------------------------------------------- helpers

    /** Milliseconds since the last subtype change - the axis the whole bug lives on. */
    private fun sinceSwitch(): String =
        if (switchedAt < 0) "[no switch yet]" else "+${SystemClock.uptimeMillis() - switchedAt}ms"

    private fun describeSubtype(subtype: Any?): String = try {
        if (subtype == null) "null" else
            "locale=${XposedHelpers.callMethod(subtype, "getLanguageTag")} " +
                "mode=${XposedHelpers.callMethod(subtype, "getMode")}"
    } catch (t: Throwable) {
        subtype?.toString() ?: "null"
    }
}
