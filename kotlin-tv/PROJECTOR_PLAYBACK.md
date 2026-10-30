# 极米连续播放画面异常排查（0.10.11）

## 反馈与证据边界

极米 Z6X 连续播放几集后，某一集画面出现抖动和模糊；换集或刷新可恢复。
本次设备未开机，没有取得该投影的系统版本、解码器日志和异常画面，原因尚未确认。
应区分硬件解码/输出层异常、投影运动补偿（MEMC）伪影和片源本身的问题。

反馈版本的播放器为 AndroidX Media3 / ExoPlayer 1.8.0，默认使用 Android MediaCodec。
每集选择一个固定清晰度的 MP4，网络慢通常导致缓冲；并没有自适应码率降档。
预载只准备下一集的 URL、索引和前 2 MiB 原始字节，不运行第二个播放器。
Media3 的 stop 会重置渲染器，但应用此前在正常切集、同集刷新时保留 PlayerView 和 Surface。
因此不能仅凭“复用 ExoPlayer 对象”断言解码器没有被释放。

## 本次兼容处理

- Media3 / ExoPlayer 与 UI 组件统一升级到稳定版 1.11.1（2026-09-10），
  包含旧系统帧率变化、输出层切换丢帧、解码器刷新及帧率估计相关修复。
  Google Maven 的 AAR 元数据要求 compileSdk 36，工程满足；未采用 alpha 版本。
  相关修复并不证明用户遇到的是同一个问题，也不能替换厂商的 MediaCodec 驱动。
- 根据系统厂商、品牌、型号、设备名、产品名识别 XGIMI/极米/Z6X。
- 正常切集保留原来的 ExoPlayer 和 PlayerView，继续使用已预载的视频数据。
  不因设备属于极米就每集重建输出层，避免短剧频繁切集时增加黑屏和初始化时间。
  不降低用户选择的清晰度。
- 极米使用同步 MediaCodec 队列，保留解码器初始化失败时的回退机制。
  对本来就使用同步队列的旧系统，此项不会改变其队列模式。
- 播放设置增加“刷新画面 · 保留进度”，强制重建播放器和输出层，并保留暂停/播放状态。
  手机网页的重试也走此路径。
- 日志新增设备标识、兼容模式、实际编码/MIME、分辨率、帧率、码率、解码器名称、
  丢帧统计和 CodecException 诊断信息；不记录签名媒体地址、内容密钥或账号信息。
- 不根据丢帧自动刷新。丢帧可能只是短时负载，自动刷新会造成播放中断和循环重试。

这是待实机验证的兼容处理，尚未证明可以消除该设备的画面异常。
手动“刷新画面”会有短暂的重新加载，正常连播不会因此每集强制重建输出层。

## Z6X 开机后的验证

1. 安装 0.10.11，保持原清晰度和 1 倍速，连续播放同一部剧至少 10 集。
2. 检查 `Playback pipeline`：极米应为 `projectorCompatibility=true`；
   正常连续切集应为 `reused=true`，首次打开、错误恢复或手动刷新应为 `reused=false`。
   如果设备识别没有命中，需要按日志中的真实系统标识补充匹配，不能假定已生效。
3. 检查 `Video input` 与 `Video output` 的实际宽高和 `Video decoder` 的名称。
   `selected=1080P` 是接口档位名称，实际编码尺寸以这些日志为准。
4. 异常发生时，记录剧名、集数、时间，先保留日志再点“播放设置 → 刷新画面”。
   确认 `Playback refresh` 出现、播放继续且观看位置保留。
5. 对同一集同一时间段复测；若机器有“运动补偿 / MEMC”，关闭后比较。
   没有解码错误并不能排除硬件输出问题；应用也无法检测投影后处理造成的伪影。

ADB 连接后可按实际设备地址采集：

```powershell
adb -s 设备IP:端口 logcat -v threadtime -s HongguoTV:V AndroidRuntime:E MediaCodecVideoRenderer:V ExoPlayerImpl:V
```

## 本机构建验证

- 播放相关核心测试：MediaCryptoTest、LowEndPlaybackTest、WatchingTest、PlaybackComfortTest，20 项通过。
- Media3 1.11.1 的 Release 构建和 lint 通过（0 errors）；APK 包名/版本及签名校验通过。
- 交付包 `outputs/hongguoTV-laozhang-v0.10.11.apk`，versionCode 28，minSdk 26，沿用 tudou 签名。
- 投影上的连续播放、输出层重建和 MEMC 对照测试仍待设备开机后执行。

官方修复记录：[AndroidX Media Release notes](https://github.com/androidx/media/blob/release/RELEASENOTES.md)。
