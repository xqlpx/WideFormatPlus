# 宽幅成片去水印（v48 落地）

## 目标
取景画幅为宽幅（65:24）时，成片一律不带水印，无论设置里水印开关是什么状态；
其余画幅完全跟随用户设置，不干预。

## 失败教训（v44）
最初拦的是 `pref_watermark_capture_switch_key`——它只是快捷面板那个入口，
跟成片链路无关。实测宽幅照片照样带水印。

## 真实读取路径（v45 探针实测）
加诊断日志后从 `WideCamera.log` 看到，宽幅激活时拍照管线读的是：

- `pref_watermark_function_key`            普通水印总开关
- `pref_hasselblad_watermark_function_key` 哈苏水印开关

读取入口是两个：

- `com.oplus.camera.data.DataManager.b(DataKey, Object)`  带默认值
- `com.oplus.camera.data.DataManager.c(DataKey)`          单参

DataKey 的 key 名字段：实测是 `f`（`d=mmkv`、`e=com.oplus.camera_preferences` 是存储信息）。
反射时 `d/e/f` 三个都读，哪个命中都算，避免混淆改名导致漏判。

## 实现
`WideCameraHook.hookWatermarkSwitch(lpparam)`，在 `handleLoadPackage` 里注册。

- 对 `DataManager.b` 和 `DataManager.c` 各挂一个 `afterHookedMethod`
- 条件：`mWideActive == true` 且该次读取的 key 命中 `WATERMARK_KEYS`
- 命中则 `param.setResult(watermarkOffValue(param.getResult()))`

`WATERMARK_KEYS` 六个：

```
pref_watermark_function_key
pref_hasselblad_watermark_function_key
pref_watermark_capture_switch_key
pref_watermark_gr_capture_switch_key
pref_watermark_makeup_function_key
pref_ai_master_watermark_photo_open_state
```

`watermarkOffValue` 按原返回值类型给关闭值，避免读取方 cast 崩：

- Boolean -> false
- Integer -> 0
- String  -> "off"
- 其它    -> false

## 关键设计点
- 只拦「拍照管线读的那一次」，不写设置。设置界面里的开关状态保持用户设定的样子，
  只是宽幅成片不认它；退出宽幅后原样放行。
- 用 after hook 而不是 before，是为了拿到原返回值来判断类型；
  同时也避免在 before 阶段访问 `c(DataKey)` 不存在的 `args[1]`（v47 的越界 bug）。
- 非宽幅路径：只做一次 `mWideActive` 判断就 return。

## 功耗 / 性能
- 无新增线程、无轮询、无 wakelock，纯事件驱动，只在相机自己读设置时插一脚。
- 非宽幅：一次布尔判断，成本约等于零。
- 宽幅且命中 key：三次反射读字段 + 一次 Set 查找，微秒级。
- 触发频率 = 相机读这些设置的次数（切画幅、开设置页、按快门前后），一天几百次量级。
- 相比模块里每 3 s 一次的空闲轮询，低好几个数量级。

## v49 优化：DataKey 实例 → 名字段缓存
- 新增 `sKeyFields`：`Collections.synchronizedMap(new WeakHashMap<>())`，
  键是 DataKey 实例，值是 `{f, d, e}` 三个名字候选。弱键防止动态 key 泄漏。
- `keyFields(key)` 命中缓存直接返回，未命中才反射一次并写入；
  `isWatermarkKey(key)` 退化成一次 Set 查找。
- 两个 hook 的判断顺序改为：先 `mWideActive`（一次 volatile 读）→ 再判 key → 命中才 setResult。
  非宽幅路径完全不碰反射；宽幅下同一个 key 也只反射一次。
- 语义等价，不影响功能：命中条件仍是「key 名精确等于 WATERMARK_KEYS 里的某一项」，
  返回值替换逻辑未变。

## v50：理光 GR 宽幅去水印 + 相册理光水印

### 问题
1. RICOH GR 模式下的宽幅成片仍带水印（普通画幅已 OK）。
2. 相册编辑宽幅 GR 照片时，不弹出「添加理光水印」选项；非宽幅 GR 照片正常。

### 问题 1 的根因与修法
GR 模式不经过常规的 ratio getter，`mWideActive` 可能始终为 false，导致
水印 hook 的 `if (!mWideActive) return;` 直接放行。

- 水印 key `pref_watermark_gr_capture_switch_key`（DataKey `Lc7/d;->k1`）本就已在
  `WATERMARK_KEYS` 里；它的值类型是 **String**（`GalleryConnectionService` 里写的是
  `"on"` / `"off"`），`watermarkOffValue` 的 String→`"off"` 正好对上。
- 修法：两个 hook 的判定从 `if (!mWideActive) return;`
  改为 `if (!mWideActive && !isWideSelected(lp)) return;`。
  `isWideSelected` 直接问 DataManager 当前 ratio 是不是 `"wide"`，
  绕开「ratio getter 有没有被调到」这个前提。
- 顺序仍是先 `isWatermarkKey(key)`（命中缓存，几乎零成本），
  再判宽幅，最后才 setResult，非水印 key 的读取不受影响。

### 问题 2 的根因与修法
裁切走的是 `Bitmap.compress` 重编码，只保留了少数标准 EXIF tag，
XMP / MakerNote / OPPO 私有 APP 段全丢——相册判断「这张能不能加理光水印」
靠的正是这些非标准元数据。

- 新增 `spliceJpeg(original, cropped)`：不动像素以外的任何东西。
  - 从**原图**原样拷贝 APPn（0xE0–0xEF）+ COM（0xFE）段；
  - 从**裁切图**拷贝帧段（DQT/SOF/DHT 等非 APP 段）；
  - 直接接上裁切图的 SOS→EOI 扫描数据。
- 结果：SOI + 原图全部元数据段 + 裁切帧 + 裁切扫描数据，
  XMP/MakerNote/私有段一并保住。
- `cropJpegIfWide` 的调用点由 `preserveExif(data, out)` 换成 `spliceJpeg(data, out)`；
  旧的 `preserveExif` 保留但不再被调用（死代码，不影响编译）。
- 段解析用 `copySegments()`：遇到 SOS(0xDA)/EOI(0xD9) 或长度非法即停，
  防止畸形 JPEG 把整个文件读穿。

### v50 实测翻车点
- GR 宽幅仍有水印：宽幅判定只认 mWideActive，GR 那条路没走到就放行了。
- 相册点编辑报「照片正在加载」：spliceJpeg 把**原图的整段 APP1 EXIF** 原样搬过去，
  里面的 ImageWidth/ImageLength 和缩略图还是裁切前的尺寸，和新的扫描数据对不上，
  相册解码到一半就卡住。

### v51–v54 修法
- **宽幅判定双保险**：hook 里改成 `mWideActive || isWideSelected(lp)`。
  `isWideSelected` 直接问 DataManager 当前 ratio 是不是 `"wide"`，绕开
  「ratio getter 有没有被调到」这个前提。
  （曾试过在 hookWatermarkSwitch 开头做 eager 解析 `wa.e.b`，结果整个方法被带崩、
  连 `hooked DataManager.b` 都不打了 —— 已回退，不要在里面放 try 外的调用。）
- **spliceJpeg 改成安全版**：
  - 第二参数改成 `preserveExif(data, out)` 的输出（EXIF 是按裁切后像素写的，尺寸正确）；
  - 再从原图**只**搬非 EXIF 的 APPn/COM（XMP、ICC、OPPO 私有段），
    用 `isExifApp1()` 把原图的 `Exif\0\0` 那份 APP1 跳过；
  - 最后 `isReadableJpeg()` 做一次 bounds-only 解码自检，不可读就退回 base。
- **GR 水印的真身**：v53 宽幅探针（任何名字含 watermark 的读取都打日志）显示，
  GR 模式下拍照管线读的是 AI 大师水印那一组：
  `pref_ai_master_watermark_photo_open_state`、
  `pref_ai_master_watermark_mode_limit_open_state`、
  `pref_ai_master_watermark_photo_style_id = personalize_film_realme_1`、
  `pref_ai_master_watermark_mode_limit_style_id = gr_style_4`。
  原来只拦了 `photo_open_state`，v54 把 `mode_limit_open_state` 也纳入 WATERMARK_KEYS。

### v55：把原图整段 EXIF 搬回来（针对相册理光水印）

用 `_tools/exif_tags.py` 对比「相机原生照片」与「裁切后照片」的 EXIF：

- 原生：IFD0 有 11 个 tag（含 011A/011B/0128/0213），ExifIFD 有 38 个 tag，
  其中包含 `927C`(MakerNote)、`A002/A003`(像素尺寸)、`9286`(UserComment)、
  `A217/A301/A402..A406/A434` 等一串厂商私有字段。
- 裁切后：只剩 IFD0 7 个 + ExifIFD 11 个标准 tag —— `preserveExif` 走
  ExifInterface 白名单，私有字段全丢。相册判断「能不能加理光水印」多半就靠这些。

改法：`spliceJpeg(original, cropped, nw, nh)`

- 不再拿 `preserveExif` 的 EXIF 当底；`copyPatchedExif()` 把原图 APP1 EXIF
  整段逐字节搬过来，MakerNote 与私有 tag 全部保留。
- 只做两处外科手术：`patchTiffDims()` 改写 IFD0 `0x0100/0x0101` 与 ExifIFD
  `0xA002/0xA003` 为裁切后尺寸；再把 IFD1（缩略图）指针清零，避免旧缩略图
  与新主图不一致。
- 输出结构：`SOI + 裁切帧 APP0/COM + 原图 XMP/ICC/私有 APPn + 修补后的 EXIF
  + 裁切帧段 + 扫描`，末尾 `isReadableJpeg()` 自检兜底，不可读就退回纯裁切帧。
- 离线验证（`_tools/patch_sim.py` 对原生照片跑同一套补丁）：
  `patched IFD0 0101 -> 4096`、`patched ExifIFD A002 -> 1515`、`A003 -> 4096`、
  `IFD1 pointer zeroed @154`，补丁后 tag 列表与原生完全一致（927C/9286/A217/
  A301/A402..A406/A434 都在）。

### v56 / v57：两处收尾
- **Orientation 归一到 1**：`patchIfd()` 里遇到 `0x0112` 就写 1。裁切后的像素本来
  就存成显示方向，把原图的旋转 tag 原样带过来会让相册转错。（实测相机原生存的
  照片 `0112` 本来就是 1，所以这是保险丝，不是主刀。）
- **EXIF 排在 APP1 的第一位**：先写修补后的 EXIF，再写原图的 XMP/ICC/私有 APPn，
  和相机原生文件的段序一致，免得某些「只认第一个 APP1」的解析器撞上 XMP 头。

### 真机验证（v57 产出的宽幅 GR 照片）

`IMG20260921155243.jpg`（743898 B）：

```
segs: E0/16 E1/926 E2/536 E2/34 E2/88 E1/17587 E1/955 E2/88 E2/564 DB/67 DB/67 C0/17 ... SOS
IFD0 n=11: 0100 0101 010F 0110 0112 011A 011B 0128 0132 0213 8769
  keyvals: 0100=1515  0101=4096  0112=1
ExifIFD n=38: 0001 0002 829A 829D 8822 8827 9000 9003 9004 9011 9101 9201 9202 9203 9204 9205 9207 9208 9209 920A 927C 9286 9290 9291 9292 A000 A001 A002 A003 A005 A217 A301 A402 A403 A404 A405 A406 A434
  keyvals: A002=1515  A003=4096
```

和相机原生照片的 tag 清单完全一致（`927C` MakerNote、`9286` UserComment、
`A217/A301/A402..A406/A434` 等私有字段全在），尺寸 tag 已按裁切后改写，
Orientation=1。相册要认的信息一个不少。

### v58：段复制诊断 + 一个新发现

`copyForeignMetadata()` 加了一行 `foreign meta kept: ...`，实拍一张后看到：

```
foreign meta kept: e0/16 e1/926 e2/536 e2/34 e2/88      <- 裁切帧那一侧
foreign meta kept: e1/954 e2/88 e2/564                  <- 原图那一侧
```

对比相机原生照片的段表 `E1/11364 E1/1683 E2/88 E2/564 E4/65535 E4/1104`，
**宽幅原图天然就没有 `E4` 那两段**（64KB 量级的 OPPO 私有 APP4）。不是我们弄丢的，
是相机在「水印被抑制」的那条路径上本来就不写它。

`E4` 的身份已经查清：段头是 `QTI Debug Metadata` —— 高通平台的调试元数据，
和水印无关。宽幅原图没这两段只是因为它没走调试采集，**这条线排除**。

真正的差异转移到了 XMP：相机原生存的照片 XMP 有 1683 字节，宽幅原图的 XMP
只有 954 字节，差了 700 多字节。需要对比内容，确认里面是否有
「水印风格 / AI 大师水印」相关字段——若有，就是相册判定的依据。

### v59：不再把编码器的 APP 段带进成品

`_tools/seg_dump.py` 把段摊开后发现，裁切帧（`Bitmap.compress` 的输出）自己带了一整套
Ultra-HDR 增益图元数据：`APP1/926`(XMP) + `APP2/536`(ICC) + `APP2/34`(GContainer
`urn:iso:std:iso:ts:21496:-1`) + `APP2/88`(MPF)。再叠加原图那一套，成品里就出现了
**两份 XMP、两份 ICC、两份 MPF**，而且排在最前面的是编码器的增益图 XMP，不是相机的
XMP —— 相册只认第一份的话，就看不到 GR 风格信息。

改法：`spliceJpeg()` 只从裁切帧拷 `APP0`(JFIF)，其余 APP 段一律不带；元数据全部来自
原图（EXIF 打头，随后 XMP/MPF/ICC），段序与相机原生 JPEG 同类。增益图若还留在扫描
数据里也只是无描述的多余数据，解码器只读第一张图，不影响显示。

### v59 实测（16:10 产出的宽幅 GR 照片）

`IMG20260921161039.jpg` 的段表已经和相机原生 JPEG 同类，只多一层 JFIF：

```
APP0/16  APP1/18775(EXIF)  APP1/955(XMP)  APP2/88(MPF)  APP2/564(ICC)  APP4/65535  APP4/4163
```

原来那两份 XMP/ICC/MPF 没了，`foreign meta kept` 日志也从两条变一条。XMP 只剩相机自己
那份（`Container`/`Item`/`hdrgm` 命名空间），不再被编码器的增益图 XMP 顶在前面；
EXIF 里 `927C` MakerNote、`A217/A301/A402..A406/A434` 等私有 tag 全在，
尺寸 `0x0100=1515 / 0x0101=4096`、`A002/A003` 同步改写，Orientation=1。

### 产物
### v60 / v61：裁切后把 MediaStore 记录补上（相册理光水印的真根因）

`cropFileInPlace()` 里其实早就写了整套通知逻辑：
`ContentResolver.query` 查 `_id` → `update(DATE_MODIFIED)` → `notifyChange` →
`MediaScannerConnection.scanFile`。但日志一直是：

```
post-crop FILE IMG20260921161039.jpg 4764938 -> 2588466
crop-notify ctx=false file=IMG20260921161039.jpg
```

`getAppContext()` 只吊在 `AndroidAppHelper.currentApplication()` 一根绳上，
在后处理线程里返回 null，于是 `if (ctx != null)` 整段被跳过 —— **通知从未执行过**。

后果：库里记的还是裁切前的宽高。

```
IMG20260921161039.jpg  库里 2716x5888 / _size=4764938   磁盘 2178x5888 / 2588466
IMG20260921161044.jpg  库里 1888x4096 / _size=4200363   磁盘 1515x4096 / 1361753
```

相册挑水印模板时读的正是这份宽高，看到的是裁切前的比例 → 理光水印模板不匹配 → 不给选项。

修法：

- `getAppContext()` 改成三路兜底并缓存（`AndroidAppHelper.currentApplication()` →
  `ActivityThread.currentApplication()` → `ActivityThread.systemMain().getApplication()`）；
- 再兜一层：三条路都拿不到时，`Runtime.exec` 喊一次
  `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://<path>`。

手工重扫验证过这条路本身是通的（四张宽幅照片的库里宽高/大小立刻刷成裁切后的值）：

```
IMG20260921161044.jpg  1515x4096   _size=1361753
IMG20260921161039.jpg  2178x5888   _size=2588466
IMG20260921160041.jpg  1515x4096   _size=681829
IMG20260921155243.jpg  1515x4096   _size=743898
```

### 产物
### v62：相册侧排查收口（照片侧已完成）

照片侧 v55–v61 已经把裁切产物对齐到相机原生：

- EXIF 整段搬运（MakerNote `927C`、`A217/A301/A402..A406/A434` 全在），尺寸 `0x0100/0x0101`、
  `A002/A003` 改写，IFD1 缩略图指针清零，Orientation 归一为 1；
- 段结构：`APP0 + EXIF + XMP + MPF + ICC`，单份，EXIF 排在最前；
- MediaStore 记录随裁切自动刷新（v60/v61 修好 `getAppContext()` 后实测
  `crop-notify ctx=true` / `resolved id=52100` / `invalidated thumbnail id=52100`）。

相册侧这条线逐一排除，过程记在 `_reverse_camera/NOTES_gallery_watermark_channel.md`：

| 怀疑点 | 结论 |
| --- | --- |
| 照片 EXIF 私有 tag 丢失 | 已修（v55–v59），实测与相机原生 tag 清单一致 |
| XMP 缺水印字段 | 排除：两边 XMP 都是增益图容器 |
| MediaStore 宽高陈旧 | 已修（v60/v61） |
| MediaStore 有水印列 | 排除：全列无任何水印字段 |
| 理光模板有比例限制 | 排除：`gr_style_4.json` 只有 `baseImageSize:360` 与相对边距 |
| `forbidden_styles` 禁了 gr | 排除：静态项目配置，若命中则非宽幅同样加不了 |
| 编辑期读相机 DataKey 被拦 | 排除：v62 调用栈探针实测，编辑期不读相机 DataManager |

剩下只可能是相册**自身**的选择逻辑（不依赖照片元数据、不依赖相机状态）。

### 产物
- APK：`/sdcard/WideCameraEnabler_v62_caller.apk`，md5 `2eccdead3fd8f142979a473052a5d7f0`

## 回退决定：放弃相册理光水印方向（2026-09-21）

用户实测宽幅 GR 照片在相册编辑时仍无「理光水印」选项，确认相册自身选择逻辑既不依赖照片元数据、也不依赖相机状态，模块侧无法干预。决定停止该方向。

- **回退点：`v54_aiwm`**（`/sdcard/WideCameraEnabler_v54_aiwm.apk`，15:30 构建，大小 874370）
  - 该版含问题 1（宽幅 GR 成片自动去水印）的完整修复，`WATERMARK_KEYS` 已含 `pref_ai_master_watermark_mode_limit_open_state`；
  - 不含 v55–v62 的「原图 EXIF 整段搬运 / 段结构对齐 / MediaStore 通知补丁」这批针对相册水印的改动。
- **设备已回退**：v54 APK 经 `/data/local/tmp` 中转后 `pm install -r` 成功（`/sdcard` 是 fuse，system_server 直接读会报 `no access to read file context u:object_r:fuse:s0`，必须先 cp 到 `/data/local/tmp`）。
- **源码现状**：工作区 `WideCameraHook.java` 仍为 v62 形态（含 `copyPatchedExif` / `patchTiffDims` / `copyForeignMetadata` 重构 / `getAppContext` 三路兜底 / `spliceJpeg(original, cropped, nw, nh)`）。工程无 git，未做逐行回退。
  - 其中 `getAppContext()` 三路兜底修掉的是「裁切后 MediaStore 通知从未执行」的真 bug，与相册水印无关，**建议保留**；
  - 若后续要重建与 v54 等效的版本，只需把 `spliceJpeg` 的调用点与实现回退到 v54 安全版（用 `preserveExif` 输出当底、仅拷非 EXIF APPn），其余可留。
- 日志：`hooked DataManager.b (wide watermark off)`、`crop-notify ctx=true file=...`
- 唯一报错仍是那条无害的 `hook FileChannel.write FAILED: Cannot hook abstract methods`
- 待用户实测：相册编辑宽幅 GR 照片时能出现「理光水印」选项
- 日志：`hooked DataManager.b (wide watermark off)`、`preview band ON 1158x3136 bars=141`、
  `cropped JPEG ... exif=true`、`crop-notify ctx=true file=IMG20260921162814.jpg`、
  `crop-notify resolved id=52100`、`invalidated thumbnail id=52100`
- 唯一报错仍是那条无害的 `hook FileChannel.write FAILED: Cannot hook abstract methods`
- 待用户实测：相册编辑宽幅 GR 照片时能出现「理光水印」选项
- 日志：`hooked DataManager.b (wide watermark off)`、`preview band ON 1158x3136 bars=141`、
  `cropped JPEG 1888x4096 -> 1515x4096 (65:24) exif=true`、`foreign meta kept: ...`
- 唯一报错仍是那条无害的 `hook FileChannel.write FAILED: Cannot hook abstract methods`

## v63：拍摄参数水印修复（基于 v54 + 整段 EXIF 搬运）

用户反馈：相册给宽幅照片加「带拍摄参数的水印」时只显示机型，光圈 / 快门 / ISO / 焦距都不显示。

取证（`_tools/exif_tags.py` 对比最新照片）：

- 相机原生 GR 照片 ExifIFD 有 38 个 tag，含 `927C`(MakerNote)、`A217/A301/A402/A403/A404/A405/A406/A434` 等 OPPO 私有字段；
- v54 产出的宽幅照片 ExifIFD 只剩 11 个标准 tag（`9000 9003 9202 A403 9004 8827 920A 829A 9209 9208 829D`），私有参数块全丢——v54 的 `preserveExif` 走 ExifInterface 白名单，机型（IFD0 `0110`）在，而相册参数水印读的私有曝光块不在。

修法：`spliceJpeg` 不再拿 `preserveExif` 的输出当底，改为 `copyPatchedExif()` 把**原图 APP1 EXIF 整段逐字节搬运**（MakerNote + 全部私有 tag 保留），只做两处外科手术：

- `patchTiffDims()` 改写 IFD0 `0x0100/0x0101` 与 ExifIFD `0xA002/0xA003` 为裁切后尺寸；
- IFD1 缩略图指针清零、Orientation 归一为 1。

这与 v55–v59 的做法一致，是参数水印能读到私有曝光块的前提；段结构与 MediaStore 通知沿用当前实现，不影响参数。

版本：**v63**（= v54 行为 + 整段 EXIF 搬运 / 拍摄参数修复）。

### v64 / v65：竖屏宽幅「分界线 + 下半灰罩」修复（Ultra-HDR 容器声明与单图载荷不一致）

v63 装机后实测反馈两条：参数仍只显示机型；竖屏宽幅成片从中下部分起有一条硬分界线，线以下叠了一层淡灰遮罩，像色阶断层。

取证（`_tools/seg_dump.py` + `_tools/xmp_dump.py`）：

- 成片段表：`APP0 + APP1(EXIF) + APP1(XMP 954B) + APP2/88(MPF) + APP2/564(ICC)`；
- MPF 头 `MPF\0MM\0*` 的 `NumberOfImages` = **2**（主图 + GainMap）；
- XMP 里是 Google Container 目录，`Item:Semantic="Primary"` + `Item:Semantic="GainMap" Item:Length=92395`。
- 但 splice 出来的扫描数据只有**一张**——原图是 Ultra-HDR 容器，我们只搬了主图。声明 2 图、实体 1 图 → 相册编辑按增益图往下解码，错位叠在下半幅上，就是那条硬分界线 + 灰罩。v59 当年判定「增益图是无描述的多余数据」是错的：MPF 与 XMP 恰恰就是那份描述。

修法：

- **v64**：`copyForeignMetadata()` 遇到 `MPF\0` 开头的 APP2 直接丢弃（`isMpf()`），日志 `dropped stale MPF segment`。
- **v65**：连宽幅原图自带的那份 XMP 也一并丢弃（`isGainMapXmp()`，判据为 APP1 且 payload 含 `GainMap` 字串），日志 `dropped gain-map XMP`。成品退化为干净的单图 JPEG。

产物：

- `v64`：`/sdcard/WideCameraEnabler_v64_nompf.apk`，md5 `706c45d9b283b250238a5961b9b59b9c`
- `v65`：`/sdcard/WideCameraEnabler_v65_nohdr.apk`，md5 `008316954b9c8e1ccaceda557756ec11`（已 `pm install -r` 成功）
- 待用户重启相机进程 / 作用域后实测：竖屏宽幅分界线是否消失；参数水印是否随 HDR 容器一起被去掉后恢复正常（若参数仍缺，说明相册参数源既不是标准 EXIF 也不是这份 XMP，需另找）。

### v66：恢复 Ultra HDR（裁切增益图，声明与实体对齐）

用户追问：v65 丢掉了 MPF + GainMap XMP，分界线是没了，但成品不再是 Ultra HDR。要求既保留 Ultra HDR 又不出异常。

取证（`_tools/mpf_dump.py` + 手写 MPF 解析）：

- 宽幅原图是真正的 Ultra-HDR 容器：`MPF NumberOfImages=2`，`img[0]` 是主图、`img[1]` 是增益图；增益图分辨率为**主图的 1/2**（`1515x4096` 对 `758x2048`，`4096x1515` 对 `2048x758`）；
- 增益图自身是一张独立 JPEG，带 `APP0 + APP1 + APP2(ISO21496-1 增益元数据)` 段，位于原图末尾；
- MPF 的 `Individual Image Data Offset` 是**相对文件起点**的偏移（`img[0].offset == 0` 正对文件开头的 SOI）；
- v63 的问题不是"声明双图"本身，而是**主图被裁、增益图没裁**，且增益图根本没被搬进成片。声明与实体对不上，相册按增益图往下解码才叠出硬分界线 + 半幅灰罩。

修法（`WideCameraHook.java`）：

- 新增 `findGainMap()` / `mpfGainEntry()`：从原图 APP2 `MPF\0` 解析 `img[1]` 的 offset / size；
- 新增 `cropGainMap()`：取出增益图，按主图同一裁切矩形（`x*gw/ow, y*gh/oh, nw*gw/ow, nh*gh/oh`）等比裁切，重编码为 JPEG，并**拼回原增益图自带的 APPn**（保住 ISO21496-1 的 gamma/min/max 增益元数据）；
- 新增 `patchMpf()` / `patchXmpGainLength()`：把成片 MPF 的 `img[0].size`、`img[1].offset`、`img[1].size` 以及 XMP `Item:Length` 改成与重组后的容器一致；
- `spliceJpeg()` 编排：裁切后的增益图追加在主图扫描之后，回填声明；只有 `patchMpf` 成功且 JPEG 可读才保留 HDR，否则退回纯裁切帧（宁可不带 HDR，也不给半坏容器）；
- 新增 `mCropRect`（ThreadLocal，由 `cropJpegIfWide` 写入 `{w,h,x,y}`）与 `mSkipHdr`（`copyForeignMetadata` 据此决定是否丢弃 HDR 段）。

产物：

- `v66`：`/sdcard/WideCameraEnabler_v66_ultrahdr.apk`，md5 `9c42648fc394183563d297ed7a118e29`（879233 字节，已 `pm install -r` 成功）
- 待用户重启相机进程 / 作用域后实测：竖屏宽幅分界线是否消失、Ultra HDR 是否保留、参数水印是否恢复。
- 已知残余风险：若增益图重编码后长度位数与原 XMP 的 `Item:Length` 不一致，`patchXmpGainLength` 会放弃改 XMP（MPF 仍已修正，增益图靠 MPF 定位，一般不影响显示）。

### v67：撤回「修参数」改动 + Ultra HDR 失败取证

用户实测反馈：

- v66 的分界线**消失了**（说明 HDR 容器声明与实体不一致这条思路是对的）；
- 但 Ultra HDR **没保住**——v66 产出的宽幅照片段结构里既无 `MPF` 也无 `XMP`，只有 `APP0 + APP1(EXIF) + APP2(ICC) + APP4×2`，即 `keepHdr == false`，走了纯单图兜底；
- 相册里**参数已经显示了**——说明之前的参数问题其实出在用户所用的**第三方水印**本身，和照片元数据无关。用户要求把「为参数做的那批改动」撤回。

#### 撤回内容（回到 v54 的白名单路径）

- `spliceJpeg()` 里 `copyPatchedExif(original, out, nw, nh)` → 改为 `copyExifApp1(preserveExif(original, cropped), out)`；
- 新增 `copyExifApp1(byte[], out)`：从 `preserveExif` 的输出里拷出第一个 EXIF APP1 段；
- `copyPatchedExif` / `patchTiffDims` / `patchIfd` 保留但不再被调用（死代码）。
- 保留未撤：`getAppContext()` 三路兜底 + MediaStore 通知（v60/v61）——那是「裁切后 MediaStore 记录从不刷新」的真 bug 修复，与参数无关。

#### Ultra HDR 失败取证

`keepHdr == false` 说明 `cropGainMap()` 返回了 null，可能是：`findGainMap()` 找不到 MPF、MPF 解出的 `off + size` 超出 `original.length`、或增益图解码失败。

已加诊断：

- `spliceJpeg()` 在 `!keepHdr` 时打日志 `hdr: no gain map (original=<len>, rect=<bool>)`；
- 新增 `dumpOriginalOnce()`：把未改动的相机原始 JPEG 存到 `/sdcard/Android/data/com.oplus.camera/files/wce_dbg_orig.jpg`（仅一次），供离线确认相机到底有没有把增益图交给我们。

产物：

- `v67`：`/sdcard/WideCameraEnabler_v67_exifrevert.apk`，md5 `58e5bbf5631a7c9ed9f79c9e9a831369`（879635 字节，已 `pm install -r` 成功）
- 待用户重启相机进程 / 作用域后：拍一张竖屏宽幅，之后即可从 `wce_dbg_orig.jpg` 判断相机原始数据是否含增益图，从而决定 Ultra HDR 还能不能救。

### v68 / v69：参数改动加回 + Ultra HDR 真根因（增益图是分开写的）

用户实测反馈：v67（白名单 EXIF）确实把镜头数据、EV 等字段丢了；Ultra HDR 仍是失效状态。所以参数改动要加回，同时继续修 HDR。

#### v68：整段 EXIF 搬运加回

`spliceJpeg()` 里 `copyExifApp1(preserveExif(original, cropped), out)` → 改回 `copyPatchedExif(original, out, nw, nh)`。白名单路径保不住 `A434`(LensModel)、`9204`(ExposureBiasValue/EV) 等，实测确认丢字段，撤回该实验。

#### v69：Ultra HDR 的真根因——增益图在第二次 write 里

对 `IMG20260921235458.jpg`（v67 产出）取证：

- 主图 `2178x5888`（65:24）；
- 文件末尾 `1147991` 处还有一张**独立的 JPEG**，到 EOF `81897` 字节，段结构 `APP0 + APP1/52 + APP1/623 + APP2/91 + DQT + DQT + SOF 1089x2944 + DHT×4 + APP2/32`；
- 其 SOF 尺寸 `1089x2944` **正好是主图的一半**，段里带 `APP2/32`（ISO21496-1 增益元数据）。

结论：**相机把主图和增益图分成两次 `write()` 写出**。我们的 `spliceJpeg` 只拿到第一次（主图），所以 `findGainMap` 永远找不到增益图，v66 的 `cropGainMap` 必然失败。而增益图由相机自己追加到文件末尾，尺寸已经与裁切后的主图一致（一半）。

修法（`WideCameraHook.java`）：

- `spliceJpeg()` 不再裁切/追加增益图，改为 `mSkipHdr = false`：**保留原图的 MPF 与 GainMap XMP**；
- 主图写完后，取 `mainLen = out.size()`，调用新增的 `patchMpfOffset(result, mainLen)`：把 MPF `img[0].size` 与 `img[1].offset` 改成裁切后主图的长度——增益图随后正好落在 `mainLen` 处；`img[1].size` 保持相机声明的原值；
- 若文件里根本没有 MPF（普通单图 JPEG），`patchMpfOffset` 返回 false，成品仍按普通单图返回。
- 原 `cropGainMap` / `findGainMap` / `mpfGainEntry` / `patchMpf` / `patchXmpGainLength` / `dumpOriginalOnce` 保留为死代码（不再被调用）。

产物：

- `v69`：`/sdcard/WideCameraEnabler_v69_mpfrepair.apk`，md5 `1ad8c9e0d508bc71b26bc1b52ad9658c`（879668 字节，已 `pm install -r` 成功）
- 待用户重启相机进程 / 作用域后实测：竖屏宽幅分界线是否消失、Ultra HDR 是否保留、镜头数据/EV 等参数是否随整段搬运一起回来。

### v70：回退到「灰罩实验之前」的基线（保留参数修复）

用户实测 v69：参数回来了、Ultra HDR 也回来了，但灰罩也回来了。用户自行定位到现象本质——观看 HDR 图片时**下半部分不会提亮**，也就是增益图只被部分应用。用户判断：之前的灰罩修复策略可能是错的，为避免一错再错，要求先回退到「修灰罩之前的稳定版」，再叠加参数修复，然后重新设计灰罩处理。

#### 回退内容

`spliceJpeg()` 去掉全部增益图相关手术：

- 不再调用 `cropGainMap()` / `patchMpf()` / `patchMpfOffset()` / `patchXmpGainLength()`；
- 不再追加任何增益图数据；
- `mSkipHdr` 固定为 `false`，即 `copyForeignMetadata()` 原样拷贝原图的 APPn（含 `MPF`、GainMap `XMP`、`ICC`、`APP4`）。
- 保留：`copyPatchedExif()`（v68 的整段 EXIF 搬运 = 参数修复）。

结果：成品 = `SOI + JFIF + 整段 EXIF + XMP + MPF + ICC + APP4 + 裁切帧 + 扫描`，与 v62 时期的形态一致，只是 EXIF 从白名单换成了整段搬运。`cropGainMap` / `findGainMap` / `mpfGainEntry` / `patchMpf` / `patchMpfOffset` / `patchXmpGainLength` / `dumpOriginalOnce` 全部保留为死代码，方便后续复用。

#### 关键取证（供后续设计灰罩修法）

对 v69 产出的两张照片量了增益图的真实边界：

| 照片 | 主图 | 增益图 SOI | 增益图实际长度（到 EOI） | XMP/MPF 声明长度 |
| --- | --- | --- | --- | --- |
| `IMG20260922001206` | 2178x5888 | 2974147 | 668312 | 522058 |
| `IMG20260922001241` | 1515x4096 | 586225 | 43546 | 60424 |

两张的增益图都**完整**（EOI 正好落在文件末尾），尺寸都是主图的一半，且比例同为 65:24。但**声明的长度与实际不符**：第一张声明比实际短 146254 字节，第二张声明比实际长 16878 字节。相册若按声明长度读取，第一张只会拿到前 ~78% 的增益数据——这正对应「下半部分不提亮」的灰罩。

结论（待验证）：灰罩的根因不是「增益图没搬」，而是**MPF/XMP 里声明的增益图长度与相机实际写入的长度对不上**。下一步修法应围绕「让声明长度与真实长度一致」设计，而不是再去裁切/追加增益图。

产物：

- `v70`：`/sdcard/WideCameraEnabler_v70_rollback.apk`，md5 `7d51c4a9a685678fbd776f4dd2dc6464`（879575 字节，已 `pm install -r` 成功）
- 待用户重启相机进程 / 作用域后实测：回到「参数在、无 HDR 手术」的基线，确认灰罩表现，作为后续对比基准。

### v71：声明长度对齐（灰罩修法）

v70 基线实测（白底竖屏宽幅 `IMG20260922002931.jpg`，1199641 字节）：

- 主图 SOI@2，主图 EOI 结束 @1007041，SOF 1515x4096
- 增益图 SOI@1007041，**实际长度 192600**，SOF 758x2048，结尾 FFD9 完整
- MPF base=16028；`img[0].size=1874124`、`img[1].off=1858114`、`img[1].size=154298`，XMP `Item:Length="154298"` —— 全是**原图时代的旧值**，abs 早已飞过文件尾

关键比值：`154298 / 192600 ≈ 80%`。相册按声明只读了增益图前 80% 的熵数据，增益只覆盖上方约 80% 高度 → 下方不提亮 = 灰罩。白底对比度高，所以格外明显。

**结论：灰罩根因不是「没搬增益图」，而是「MPF/XMP 声明与实体不一致」。**

修法（v71，不改 v70 的 spliceJpeg 主逻辑）：

- 新增 `FileOutputStream.write` 的 `afterHookedMethod`：写完成后若 `mWideActive` 且路径为 .jpg、文件 500KB~4MB，调用 `alignHdrGainDeclarations(f)`
- `alignHdrGainDeclarations`：读文件 → 末尾必须是 FFD9 → 反向找最后一个 `FFD8FF`（增益图 SOI，且前两字节为 FFD9）→ 解析主图 MPF/XMP → 原地改写三个 32 位 MPF 字段与 XMP `Item:Length`
  - `img[0].size = gainSOI - mpfBase`
  - `img[1].off  = gainSOI - mpfBase`
  - `img[1].size = fileLen - gainSOI`
  - XMP `Item:Length = fileLen - gainSOI`（仅当新旧位数一致时原地替换）
- 用 `RandomAccessFile` 只改这 18 个字节，不整体重写
- 主图写完时文件只有一个 SOI，函数直接返回；增益图落地后才真正生效，对「一次 write」与「两次 write」两种模式都兼容

产物：

- `v71`：`/sdcard/WideCameraEnabler_v71_gainalign.apk`，md5 `232d3ef6ade59c2b962920db461cd1ee`（881637 字节，已 `pm install -r` 成功）
- 待用户重启相机进程 / 作用域后实测：白底竖屏宽幅灰罩是否消失（下半部分是否恢复提亮），Ultra HDR 是否仍保留，参数是否仍正常。
