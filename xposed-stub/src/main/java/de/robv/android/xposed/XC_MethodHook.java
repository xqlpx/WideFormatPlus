package de.robv.android.xposed;

/**
 * 编译期 stub：镜像 XposedBridge API 中 XC_MethodHook 的最小可编译形状。
 * 运行期不会被打进 APK（通过 compileOnly 引入），真实实现由 LSPosed 框架提供。
 */
public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam {
        public Object[] args;
        public Object thisObject;
        public java.lang.reflect.Member method;
        private Object result;
        private Throwable throwable;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
        }

        public void setResultAndThrowable(Object result, Throwable throwable) {
            this.result = result;
            this.throwable = throwable;
        }
    }

    public static class Unhook {
        public void unhook() {
        }
    }
}