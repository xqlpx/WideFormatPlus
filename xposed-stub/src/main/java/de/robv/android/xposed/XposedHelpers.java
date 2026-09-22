package de.robv.android.xposed;

/**
 * 编译期 stub：XposedHelpers 的最小可编译形状。
 * 运行期真实实现由 LSPosed 框架提供，本类不会被打进 APK。
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className,
            ClassLoader classLoader,
            String methodName,
            Object... parameterTypesAndCallback) {
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return null;
    }
}
