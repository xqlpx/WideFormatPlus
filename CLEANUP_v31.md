# 工作区清理记录（v31 收尾）

清理时间：2026-09-21
触发：v31 收尾后整理这期间产生的中间产物

---

## 一、/sdcard 已删

| 项目 | 说明 |
|---|---|
| `WideCameraEnabler_v1` ~ `v30` | 30 个旧版 APK，仅保留 `v31_wide.apk` |
| `oplus_camera_wide_6172*.zip` | 解包/回编译中间产物，约 330 MB |
| `oplus_camera_wide_meta*.zip` | 元数据中间产物，约 222 MB |
| `oplus_camera_wide_6172_v2_md5.txt` | 校验记录 |
| `window_dump.xml` | 临时 UI dump |
| `Download/WideCameraEnabler_v31_wide.apk` | 与根目录 v31 重复 |
| `input_6172.apk`（192 MB） | md5 `3285485a93a09cafdc7e96d9829f7966`，与工作区 `相机_6.070.172.apk` 完全相同 |
| `相机_6.106.782.apk`（251 MB） | md5 `368b9fac6b9016822f516359862f179d`，与工作区同名文件完全相同 |
| `Download/camera_6.106.782.apk`（240 MB） | 同上哈希，第三份副本 |
| `operit_jadx` | 空目录 |

## 二、工作区 `_reverse_camera` 已删（2.7 G → 1.0 G）

- `_pack_v4`（293 M）
- `_mod_v4`（82 M）
- `_v4check`（87 M）
- `dexswap`（216 M）
- `dexcheck`（14 M）
- `meta_6172_wide`、`meta_6172_wide_v2`、`meta_6172_wide_v3`（381 M）
- `meta_module`、`meta_module_stock`
- `input_6172.apk`
- `verify_v2`
- `build.log`、`build3.log`、`decoded_6172.log`

## 三、保留未动（素材/文档，非垃圾）

- `decoded_6172`（707 M）— 反编译源码，查相机内部类名、DataKey、CameraParameter 常量仍需
- `camera_6172_wide_unsigned.apk`、`_v2`、`_v3`（各 127 M）— 原生 XPAN 线路留档
- `WIDE_PATCH_v3.md` — XPAN 线路结论
- `zipalign.py`
- 工作区三份相机 APK：`相机_5.031.88.apk`、`相机_6.070.172.apk`、`相机_6.106.782.apk`
- `lsposed_wide/build` — 构建缓存，删除会导致下次编译变慢
- `lsposed_wide/CHANGELOG_v31.md`、`lsposed_wide/DESIGN_notes_thread_lifecycle.md`

## 四、待定

`/sdcard/operit_x`（457 M）

- 内容：`app.apk`（385 M）+ `classes2~10.dex` + `resources.arsc` + `assets/` + `jar_x/`
- `app.apk` md5 `c738894bbd8821a50483554707197687`，与工作区三份相机 APK 均不匹配
- 时间戳 2026-09-10，早于相机线（09-18 ~ 09-20），判断属另一条线的反编译目录
- 不属于「这期间产生的垃圾」，原样保留，等待确认

## 五、释放量

- `/sdcard`：约 1.2 G
- 工作区：约 1.7 G
