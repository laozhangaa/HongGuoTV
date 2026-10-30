# Android 8–15 兼容性排查

本次排查针对 Kotlin 原生 TV 客户端，基线为 Android 8.0 / API 26，编译与目标 SDK 为 36。工程没有 JNI 或 NDK 代码，Release APK 只包含 Kotlin/Java 与 Media3，因此 CPU ABI 不会产生单独的 native 库兼容问题。

## 已处理

- 内容接口、分类接口和详情接口遇到 DNS、连接、TLS、超时、408/425/429/5xx 时最多自动重试 3 次；页面会显示更具体的电视网络原因。页面切换或 Activity 生命周期取消时，重试会立即停止。
- Android 15 / API 35 增加了 `values-v35` 主题，避免系统边到边策略与应用自己的全屏沉浸式布局重复加内边距。Android 16 / API 36 会忽略临时退出项，但 Activity 已显式隐藏系统栏，仍按全屏路径工作。
- 更新安装包改用可替换的文件移动，修复 Android/Windows 上目标 APK 已存在时 `File.renameTo()` 拒绝覆盖导致更新失败的问题。
- 播放器继续使用 Media3 解码器回退、ByteVC2 过滤、720P 手动降级和失败自动重试。图片请求在后台解码，低内存设备缩小缓存并响应 `onTrimMemory`。
- 下一集预载从剩余约 45 秒（按当前倍速换算）开始，后台准备播放地址、MP4 索引和下一集首段 2 MiB；预载结果过期、被取消或不兼容时自动走普通播放，不会阻塞当前集。
- Android 9+ 的明文策略由 Network Security Config 控制，已允许当前视频 CDN 白名单；手机遥控是电视监听的局域网 HTTP，不依赖外网明文请求。

## 按系统版本的结果

| 系统 | 主要风险 | 当前结论 |
| --- | --- | --- |
| Android 8/8.1（API 26/27） | 老电视 CA/TLS、32 位内存、硬件 HEVC 支持差、Range 网络抖动 | API 调用满足最低版本；已加有限重试、低内存图片策略和播放器失败恢复。实际证书与硬件解码仍需目标电视实测。 |
| Android 9/10（API 28/29） | 系统默认限制明文流量、存储权限行为变化 | 明文策略已集中在 Network Security Config；应用只使用 cache 目录和 FileProvider，不申请外部存储权限。未列入 CDN 白名单的 `http://` 播放地址会被系统拒绝，接口正常返回 HTTPS 时不受影响。 |
| Android 11/12（API 30/31/32） | 分区存储、后台生命周期、返回键行为 | 没有读写共享存储；网络、图片和播放器在 `onPause/onStop` 取消并释放；返回键保留 TV 自定义导航。 |
| Android 13（API 33） | 通知运行时权限、预测返回 | 应用不发送通知，因此不需要 `POST_NOTIFICATIONS`；已关闭系统回调接管，使用兼容的 Activity 返回路径。 |
| Android 14（API 34） | 媒体解码器和全屏窗口变化 | 已在 API 34 ARM64 设备安装启动并检查应用日志；无 AndroidRuntime、ExoPlayerImplInternal 或应用致命错误，38209 手机遥控端口和 `/status` 返回正常。 |
| Android 15（API 35） | targetSdk 35+ 的边到边强制策略 | 已加入 API 35 主题兼容项；API 35 的实体 TV 仍应验证自定义弹窗和遥控焦点。 |

## 尚需目标设备确认的边界

1. Android 8 实体电视的系统根证书可能过旧，TLS 握手失败无法由重试修复；页面会提示证书/TLS 原因。
2. 某些芯片不能硬解 HEVC、AV1 或平台私有编码，即使 API 版本满足也可能黑屏；应优先选择 720P，日志中检查 `Playback failure` 的解码错误。
3. 媒体服务器必须正确支持单区间 `206 Content-Range`。当前不会把忽略 Range 的 `200` 整段响应读入内存，避免老电视因大文件 OOM；遇到该服务器应切换可用清晰度或更换源。
4. 手机和电视可能连接到不同 VLAN、DNS 或访客网络；这属于网络拓扑问题，不是 Android API 兼容问题。电视端可访问 `http://电视IP:38209/` 才能使用手机遥控。

## 验证记录

- `:core:test --tests '*ContentRepositoryTest' --tests '*LowEndPlaybackTest' --tests '*FasterBrowsingTest'`：通过。
- `:core:test --tests '*AppUpdatesTest'`：通过，覆盖已有 APK 的替换安装。
- `:app:lintRelease` 与 `:app:assembleRelease`：通过。
- Release APK：`minSdk 26`、`targetSdk 36`、双 ARM 设备无需 native 库拆分；最终实体 Android 8/15 的解码和边到边显示仍需对应设备完成一次播放、切集、返回和弹窗检查。
