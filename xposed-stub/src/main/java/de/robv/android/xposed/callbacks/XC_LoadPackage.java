package de.robv.android.xposed.callbacks;

/**
 * 编译期 stub：XC_LoadPackage 的最小可编译形状。
 * 运行期真实实现由 LSPosed 框架提供，本类不会被打进 APK。
 */
public class XC_LoadPackage {

    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}