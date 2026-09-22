package de.robv.android.xposed;

/**
 * 编译期 stub：XposedBridge 的最小可编译形状。
 * 运行期真实实现由 LSPosed 框架提供，本类不会被打进 APK。
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
        // stub: 编译期空实现；运行期由 LSPosed 注入真实日志实现
    }

    public static void log(Throwable t) {
        // stub: 编译期空实现
    }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        // stub: 编译期空实现；运行期由 LSPosed 提供真实实现
        return java.util.Collections.emptySet();
    }
}