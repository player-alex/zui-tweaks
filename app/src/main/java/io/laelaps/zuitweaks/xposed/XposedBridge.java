package io.laelaps.zuitweaks.xposed;

import android.util.Log;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/** ≈ de.robv.android.xposed.XposedBridge (only the members this module used), over libXposed 101. */
public final class XposedBridge {

    private XposedBridge() {}

    public static void log(String text) {
        Log.i("ZuiTweaks", text);
        XpModule.log(text);
    }

    public static void log(Throwable t) {
        Log.e("ZuiTweaks", "", t);
        XpModule.log(Log.getStackTraceString(t));
    }

    /** Invoke the ORIGIN (unhooked) method — replaces XposedBridge.invokeOriginalMethod. */
    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args) throws Throwable {
        Method m = (Method) method;
        Object[] a = (args != null) ? args : new Object[0];
        @SuppressWarnings("rawtypes")
        XposedInterface.Invoker inv = XpModule.module.getInvoker(m);
        inv = inv.setType(XposedInterface.Invoker.Type.ORIGIN);
        return inv.invoke(thisObject, a);
    }

    public static Set<Object> hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) {
        Set<Object> handles = new HashSet<>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                m.setAccessible(true);
                handles.add(XpModule.module.hook(m).intercept(XposedHelpers.makeHooker(callback, m)));
            }
        }
        return handles;
    }
}
