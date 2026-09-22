# WideCameraHook 版本记录

## v31 — 当前稳定版（已实测通过）

- 部署包：`/sdcard/WideCameraEnabler_v31_wide.apk`
- md5：`65e8ff0a94b24b0cc4c9b2414a717a7b`
- 大小：868,053 字节
- 与工作区 build 产物（`app/build/outputs/apk/debug/app-debug.apk`）哈希一致

### 相对 v30 的增量（仅调度层，裁切核心未动）

1. **自适应轮询退避**
   - `ACTIVE_POLL_MS = 700L`：宽幅活动期全速扫盘
   - `IDLE_POLL_MS = 3000L`：空闲期睡 3 秒，**不扫 DCIM、不读尺寸、不 stat**
   - `WIDE_GRACE_MS = 60 * 1000L`：与 `CROP_WINDOW_MS` 对齐，拍完宽幅切走后 60 秒内仍全速
2. **唤醒锁**（`mWakeLock` + `wait/notify`）
   - 宽幅信号一到即 `wakePostProcessor()` 打断空闲睡眠，第一帧不漏
3. **活跃判定三条件**
   - `mWideActive` 为真 / 60 秒内见过 wide / `isWideSelected(sLp)` 实时比例偏好仍为 wide

### 已确认的取舍

- 空闲期兜底线程完全不工作（用户已接受此折中）
- 前提：相机确实不在宽幅模式，不存在该扫而未扫
- 裁切主路径为四条 hook，兜底仅为安全网

### 实测结论

- 活跃期 47 次 latch / 71 次 crop，全部 `ok=true`
- 空闲期 3 条 latch 均为 `crop=false wide=false`，普通画幅零误裁
- 相机覆写全幅帧被 `mtime:size` 签名当场捕获并重新裁切

## 版本演进关键节点

- **v22**：EXIF 回填（Make/Model/DateTime/ExposureTime/GPS）
- **v23**：连拍文件名正则放开 `_01`/`_02` 后缀
- **v26**：`mtime` 跟踪覆盖 + 尺寸诊断日志
- **v27**：`jpegSize` 只读 256 KB 前缀，替代 `readAllBytes`
- **v28**：回退水印抑制（−65 行，实测无效）
- **v29**：版本签名从 `mtime` 改为 `mtime:size`（相机覆写保留 mtime）
- **v30**：`CROP_WINDOW_MS` 3 分钟 → 60 秒
- **v31**：自适应退避 + 唤醒锁

## 未推进

- 原生 XPAN 线路：模式能进入但流配置下发失败，`/odm/etc/camera/` 硬件配置表在 camera provider 进程内，LSPosed 触及不到。用户明确推迟。
