package io.laelaps.zuitweaks.xposed;

import java.lang.reflect.Member;

/** ≈ de.robv.android.xposed.XC_MethodHook + nested MethodHookParam (compat over libXposed 101). */
public abstract class XC_MethodHook {

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        private Object result;
        private Throwable throwable;
        boolean returnEarly; // package-private: read by the shim's Hooker

        public Object getResult() {
            return result;
        }

        /**
         * Legacy semantics: a result and a throwable are mutually exclusive. Setting one
         * clears the other, which is what lets an after-hook swallow the exception the
         * original method threw by supplying a result instead.
         */
        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }
    }

    public void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    public void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
