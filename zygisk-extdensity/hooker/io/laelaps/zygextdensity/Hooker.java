package io.laelaps.zygextdensity;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

import java.lang.reflect.Method;

/**
 * LSPlant callback that corrects Display.getRealMetrics() for the external monitor.
 *
 * The ZUI defect: an app shown on the monitor gets its default Display's getRealMetrics() as the
 * monitor's pixel size (e.g. 2560x1440) with the INTERNAL panel's density (440), so
 * min(px)/density = 523 < 600 and every tablet check (react-native-device-info et al.) says phone.
 * We rewrite the density to the monitor's own value whenever the metrics carry the monitor's size
 * at a foreign density. Internal panel, non-external surfaces, and already-correct metrics are
 * untouched (no-op). Only public APIs are used, so no hidden-API access is needed.
 */
public class Hooker {

    /** Backup (original) method, set by native right after LSPlant.Hook returns.
     *  volatile: the hook can fire on another thread before native finishes wiring this. */
    public volatile Method backup;

    // Cached external-display facts (resolved lazily; the monitor may attach after process start).
    private static volatile boolean resolved = false;
    private static volatile int extMin = 0, extMax = 0, extDpi = 0;
    private static final ThreadLocal<Boolean> reentry = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() { return Boolean.FALSE; }
    };

    /** LSPlant callback: signature must be `public Object callback(Object[] args)`. */
    public Object callback(Object[] args) throws Throwable {
        Method b = backup;
        // The hook can fire before native sets `backup` (multi-threaded processes). Skipping the
        // call rather than NPE-ing keeps the app alive; getRealMetrics just returns unfilled once.
        if (b == null) return null;
        Object result = b.invoke(args[0], args[1]); // original fills args[1] (void -> null)
        try {
            if (args.length > 1 && args[1] instanceof DisplayMetrics) {
                correct((DisplayMetrics) args[1]);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static void correct(DisplayMetrics dm) {
        try {
            if (Boolean.TRUE.equals(reentry.get())) return;
            if (!resolved) resolve();
            if (extDpi <= 0 || extMin <= 0) return;
            int lo = Math.min(dm.widthPixels, dm.heightPixels);
            int hi = Math.max(dm.widthPixels, dm.heightPixels);
            if (lo != extMin || hi != extMax || dm.densityDpi == extDpi) return;
            dm.densityDpi = extDpi;
            dm.density = extDpi / 160f;
            dm.scaledDensity = extDpi / 160f;
            dm.xdpi = extDpi;
            dm.ydpi = extDpi;
        } catch (Throwable ignored) {
        }
    }

    private static void resolve() {
        reentry.set(Boolean.TRUE);
        try {
            Context ctx = currentApplication();
            if (ctx == null) return;
            DisplayManager dmgr = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dmgr == null) return;
            for (Display d : dmgr.getDisplays()) {
                if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue; // skip the internal panel
                DisplayMetrics m = new DisplayMetrics();
                d.getRealMetrics(m); // reentry guard keeps our own hook off this call -> raw values
                extMin = Math.min(m.widthPixels, m.heightPixels);
                extMax = Math.max(m.widthPixels, m.heightPixels);
                extDpi = m.densityDpi;
                resolved = true;
                return;
            }
        } catch (Throwable ignored) {
        } finally {
            reentry.set(Boolean.FALSE);
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return (Context) app;
        } catch (Throwable t) {
            return null;
        }
    }
}
