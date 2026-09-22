# WideFormat+

【自用模块】给 Realme GT8Pro 相机（6.070.172）增加 65:24 宽幅的 LSPosed 模块。

> 适配基线：RMX5200 · OplusCamera 6.070.172（versionCode 60000）· ColorOS V16.1.0 · Android 16

## 目录结构

```
lsposed_wide/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/xposed_init
│       ├── java/com/wideformat/plus/WideCameraHook.java
│       └── res/values/arrays.xml
└── README.md
```

## 目标与作用域

- 目标包名：`com.oplus.camera`
- 入口类：`com.wideformat.plus.WideCameraHook`
- Xposed 最低版本：82

## Hook 点

模块同时做「诊断」和「强制」两件事，全部写进 Xposed 日志（TAG = `WideCamera`）：

| # | 类.方法 | 处理 |
|---|---|---|
| 1 | `com.oplus.camera.configure.CameraConfig.x(String,String)Z` | 当第二个参数是 `com.oplus.camera.wide.frame.ratio.support.modelist` 时强制返回 `true` |
| 2 | `u7.o0.i0(String,ZZ)Z` | 恒返回 `true`（绕过 `wa.d.l1()` 里那条隐藏 wide 的分支） |
| 3 | `gl.b.setOptionItemsVisible(String[],boolean)V` | 当 values 含 `wide` 且 visible=false 时，改成 `true` |
| 4 | `gl.b.setOptionItems(ArrayList)V` | 只记录 key 与条目数 |
| 5 | `gn.n.J(String,String[])Z` | 记录调用与返回值 |
| 6 | `an.b.R(String,String[])V` | 当 key 是 `pref_camera_photo_ratio_key` 且 values 含 `wide` 时直接拦截返回（阻止隐藏） |

因为 v1–v4 静态改 APK 都没让宽幅出现，说明门控点还没完全定位。这套 hook 的价值是：**装上后跑一次相机，看 LSPosed 日志里到底哪条链被触发**，日志会直接告诉我们 wide 是在哪一步被过滤掉的。

## 构建

### 方式 A：AndroidIDE / 本机 Gradle

1. 首次需要生成 wrapper（若项目里没有 `gradlew`）：

   ```bash
   cd /data/data/com.ai.assistance.operit/files/workspace/相机宽幅/lsposed_wide
   gradle wrapper --gradle-version 8.4
   ```

2. 编译 debug 包：

   ```bash
   ./gradlew :app:assembleDebug
   ```

3. 产物：

   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### 方式 B：直接用 Android Studio

用 Android Studio 打开 `lsposed_wide/` 目录，Sync 后 `Build > Build APK(s)`。

> 依赖 `de.robv.android.xposed:api:82` 只做 `compileOnly`，运行期由 LSPosed 框架提供，不要打包进 APK。

## 安装与验证

1. 安装 `app-debug.apk`。
2. 打开 LSPosed 管理器 → 模块 → 启用 `WideFormat+`。
3. 在模块作用域里勾选 **相机（com.oplus.camera）**。
4. 强制停止相机（或重启一次），打开相机 → 切到照片模式 → 看比例条。
5. 抓日志：

   ```bash
   logcat -s LSPosed-Bridge | grep WideCamera
   ```

   或者在 LSPosed 管理器里直接看模块日志。

## 日志怎么读

- 如果看到 `u7.o0.i0(...) -> forced true` 但宽幅仍不出现 → 说明比例菜单不走 `wa.d` 这条链，需要继续找真正的菜单数据源。
- 如果看到 `gl.b.setOptionItemsVisible([...wide...], visible=false)` → 就是这里被隐藏的，第 3 条 hook 应该能救回来；如果没救回来，说明还有别的地方在过滤。
- 如果看到 `an.b.R(pref_camera_photo_ratio_key, [...wide...])` → 说明 `w3` 确实在隐藏 wide，第 6 条已拦截。
- 如果以上日志**一条都没有** → 说明相机进程没有被 hook 上（检查作用域/是否重启过相机进程），或者这些类名在运行期被 dex 优化改名了。

## 后续

把首次运行的 LSPosed 日志发出来，就能精确定位真正过滤 wide 的那一行，然后把这个模块收敛成只有一两处精准 hook 的成品。
