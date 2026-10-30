# 红果TV 0.10.17

版本 0.10.17 / versionCode 34，包名 `com.hongguotv.laozhangapp`，最低 API 26，沿用 tudou 签名。

## 修复

- 内容接口和视频读取优先尝试系统 DNS 返回的 IPv4 地址，避免当前测试设备上多个 IPv6 地址连接失败后的串行等待。
- 保留所有 IPv6 地址作为后备；仅有 IPv6 的网络仍可连接。不更换 DNS 服务、不写死 CDN IP、不修改设备网络设置。
- 下一集预载的独立网络客户端使用相同策略，首页预览随默认内容客户端获得修复。
- 增加取源排队、播放信息请求、视频准备各阶段耗时日志；日志不包含完整视频 URL、Cookie 或密钥。
- 视频准备失败时及时关闭新建的媒体资源。

## 验证

- 新增 4 项连接测试全部通过、0 跳过：双栈先连 IPv4，IPv4 失败回退 IPv6，仅 IPv6 可连接，以及保留 DNS 解析错误。
- 全量核心测试 121 项，119 项通过，2 项失败。`RelatedSeasonsTest.unrelatedFirstPageCanContinueButSearchIsBounded` 与 `SignerTest.matchesPinnedJavaScriptOracleAcrossTimestampVariants` 在本次修改前生成的 core.jar 中也有同样失败，已单独复现并保存，未在本次播放连接修复中修改这两个模块。
- Release 构建与 lintRelease 成功；lint 0 errors、42 warnings。
- 签名 SHA-256 与既有 APK 相同，已核对版本、包名、最低系统要求。
- 已在 `192.168.124.21:5555` 覆盖安装，保留应用数据。

同一部剧《以眼还眼：该闺蜜养我了！》实测：

| 场景 | 播放信息 | 视频准备 | 从播放器请求到首帧 | 从手机请求到状态确认播放 |
| --- | --- | --- | --- | --- |
| 安装后首次续播第 1 集 | 448ms | 251ms | 1212ms | 1891ms（包含详情加载及轮询） |
| 第 1 集从头播放 | 149ms | 20ms | 242ms | 421ms |
| 手动切到第 2 集 | 365ms | 231ms | 832ms | 1031ms |

全部选择 1080P，三个短样本内未记录播放错误、自动重试或崩溃。这是当前模拟器和当前网络的实测，不代表所有电视或所有 CDN 的固定起播时间；IPv4 连接建立后的视频传输仍可能有独立波动。

测试后返回首页，恢复手机端原剧库分类和筛选。证据位于 `outputs/verification-v0.10.17/`，包括状态记录、脱敏播放日志、测试 XML、修改前的核心 JAR 和既有失败的复现记录。

## 交付

- `outputs/hongguoTV-laozhang-v0.10.17.apk`，2,742,246 字节。
- SHA-256：`83d52f38697bf54d43cd31e8dc173534932fb7a2b3d2256aeb07309394933725`。
- `outputs/update.json`、`outputs/update-v0.10.17.json`、`outputs/SHA256SUMS-v0.10.17` 已匹配。
- 安装包和更新清单均保存在本地，未上传服务器。
- 修改前备份：`backups/before-playback-ipv4-20261029-193937/`。
