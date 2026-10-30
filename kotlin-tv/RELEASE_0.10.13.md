# 红果TV 0.10.13

版本：0.10.13 / versionCode 30，包名 `com.hongguotv.laozhangapp`，最低 Android 8.0（API 26）。沿用 `tudou` 正式签名。

## 本次播放改进

- 自动下一集和手动选集复用同一个 ExoPlayer、PlayerView 和画面输出层，切集时不再先拆除播放器。
- 切集保留上一集最后一帧，并在无缝切换期间隐藏缓冲圈，减少短剧连续播放时的黑屏和转圈。
- 继续使用下一集预载；预载源可直接交给播放器，旧源和专用 HTTP 连接延迟 1.5 秒释放。
- 播放器起播阈值调整为 500/1000ms，目标缓冲池为 12MB。
- 播放 READY 后向 Android 31+ 显示管线提示实际视频帧率，改善固定帧率投影设备的抖动风险。
- 无缝交接失败时，1.5 秒保护窗口内先回退到旧源；其它故障恢复、清晰度切换、手动刷新仍使用完整重建流程，避免错误状态污染当前播放。

## 验证

- Release 构建和 `lintRelease` 成功；lint 无 Error/Fatal，仅有项目原有弃用警告。
- 已验证包名、应用名、versionCode、versionName、minSdk 和 v2 签名；与 0.10.12 的证书 SHA-256 一致，可覆盖安装。
- 已在 MuMu 模拟器覆盖安装并启动，启动日志无 `AndroidRuntime` 崩溃。
- 极米 Z6X 当前未开机，投影实机的帧率切换和画面抖动仍需开机后连续播放多集验证。

## 交付文件

- `outputs/hongguoTV-laozhang-v0.10.13.apk`
- `outputs/update.json`
- `outputs/update-v0.10.13.json`
- `outputs/SHA256SUMS-v0.10.13`

本次历史交付的更新清单和 APK 已在本地生成，当时尚未上传更新服务器。
