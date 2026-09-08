package io.laelaps.zuitweaks.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/** ≈ de.robv.android.xposed.XposedHelpers (only the members this module used), over libXposed 101. */
public final class XposedHelpers {

    private XposedHelpers() {}

    public static Class<?> findClass(String className, ClassLoader cl) {
        try {
            return Class.forName(className, false, cl != null ? cl : XposedHelpers.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader cl) {
        try {
            return findClass(className, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object findAndHookMethod(String className, ClassLoader cl, String methodName, Object... pt) {
        try {
            return findAndHookMethod(cl.loadClass(className), methodName, pt);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object findAndHookMethod(Class<?> clazz, String methodName, Object... pt) {
        XC_MethodHook cb = (XC_MethodHook) pt[pt.length - 1];
        Class<?>[] types = new Class<?>[pt.length - 1];
        for (int i = 0; i < pt.length - 1; i++) types[i] = (Class<?>) pt[i];
        Method m = resolveMethod(clazz, methodName, types);
        m.setAccessible(true);
        return XpModule.module.hook(m).intercept(makeHooker(cb, m));
    }

    public static Object callMethod(Object obj, String name, Object... args) {
        try {
            Method m = bestMethod(obj.getClass(), name, args);
            m.setAccessible(true);
            return m.invoke(obj, args);
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String name, Object... args) {
        try {
            Method m = bestMethod(clazz, name, args);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    public static Object getObjectField(Object obj, String name) {
        try {
            return field(obj.getClass(), name).get(obj);
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    public static int getIntField(Object obj, String name) {
        try {
            return field(obj.getClass(), name).getInt(obj);
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    public static void setObjectField(Object obj, String name, Object value) {
        try {
            field(obj.getClass(), name).set(obj, value);
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    public static void setStaticBooleanField(Class<?> clazz, String name, boolean value) {
        try {
            field(clazz, name).setBoolean(null, value);
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    // ---- internals -----------------------------------------------------------------

    /** Bridges a legacy before/after callback onto libXposed's around-advice Hooker. */
    static XposedInterface.Hooker makeHooker(final XC_MethodHook cb, final Member method) {
        return chain -> {
            XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
            p.method = method;
            p.thisObject = chain.getThisObject();
            List<Object> a = chain.getArgs();
            p.args = a.toArray();
            cb.beforeHookedMethod(p);
            if (!p.returnEarly && p.getThrowable() == null) {
                try {
                    p.setResult(chain.proceed(p.args));
                } catch (Throwable t) {
                    p.setThrowable(t);
                }
            }
            cb.afterHookedMethod(p);
            if (p.getThrowable() != null) throw p.getThrowable();
            return p.getResult();
        };
    }

    /**
     * de.robv's findMethodExact used getDeclaredMethod only and failed if the requested
     * class did not declare the method. This walks up instead, which is usually what the
     * caller wanted - but it means the hook can land on a base class shared with other
     * subclasses, so every callback needs its own instance guard. Say so in the log rather
     * than resolving silently: a hook that quietly moved up a level is very hard to see.
     */
    private static Method resolveMethod(Class<?> clazz, String name, Class<?>[] types) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                if (c != clazz) {
                    XposedBridge.log("findAndHookMethod: " + name + " resolved on superclass "
                            + c.getName() + " (requested " + clazz.getName() + ")");
                }
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        try {
            return clazz.getMethod(name, types);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static Method bestMethod(Class<?> clazz, String name, Object[] args) {
        List<Method> cands = new ArrayList<>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.length) cands.add(m);
            }
        }
        if (cands.isEmpty()) throw new RuntimeException(new NoSuchMethodException(name + "/" + args.length + " on " + clazz.getName()));
        if (cands.size() == 1) return cands.get(0);
        for (Method m : cands) {
            Class<?>[] pts = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) continue;
                if (!wrap(pts[i]).isAssignableFrom(args[i].getClass())) {
                    ok = false;
                    break;
                }
            }
            if (ok) return m;
        }
        return cands.get(0);
    }

    private static Field field(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new RuntimeException(new NoSuchFieldException(name + " on " + clazz.getName()));
    }

    private static Class<?> wrap(Class<?> t) {
        if (t == int.class) return Integer.class;
        if (t == boolean.class) return Boolean.class;
        if (t == long.class) return Long.class;
        if (t == float.class) return Float.class;
        if (t == double.class) return Double.class;
        if (t == char.class) return Character.class;
        if (t == byte.class) return Byte.class;
        if (t == short.class) return Short.class;
        return t;
    }

    private static RuntimeException asRuntime(Throwable t) {
        if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) t = t.getCause();
        return (t instanceof RuntimeException) ? (RuntimeException) t : new RuntimeException(t);
    }
}
