package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 编译期 stub：Xposed 模块入口接口。
 * 运行期真实实现由 LSPosed 框架提供，本类不会被打进 APK。
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}