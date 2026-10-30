# 应用内更新

正式 APK 和更新清单发布在 [laozhangaa/HongGuoTV Releases](https://github.com/laozhangaa/HongGuoTV/releases)。仓库公开，电视检查及下载更新无需登录 GitHub。

固定清单入口：

```text
https://github.com/laozhangaa/HongGuoTV/releases/latest/download/update.json
```

## 电视上的行为

- 默认开启自动检查，每次回到应用前台后约 8 秒开始检查，播放中不打扰。
- 发现更高 `versionCode` 且支持当前系统的版本时显示提醒，只提示、不自动下载。
- 用户选择「下载新版」后下载 APK，完成校验后选择「安装新版」，由 Android 显示安装确认。
- 首次安装更新可能需要允许「安装未知应用」。同包名、同签名覆盖安装保留本机收藏、观看记录和设置。
- 选择「稍后」会推迟该版本的提醒一天；「设置 → 版本与更新」可手动检查或关闭自动检查。
- 请求或校验失败时保留当前应用；播放中或离开前台会取消更新请求。

安装前会校验文件大小、SHA-256，以及 APK 实际包名、版本、最低系统要求和与当前安装版本一致的签名。

## 从旧地址迁移

v0.10.17 及更早版本使用旧更新地址。首次需手动安装一次 v0.10.18 或更高版本，此后应用从本仓库 Releases 获取更新。

## 发布规则

1. 每次正式发布同时提高 APK 的 `versionCode`，不能只修改清单中的版本字段。
2. 完成构建与验证，使用既有正式签名签署 APK。
3. 为同一版本生成 `update.json`，其中下载地址指向本仓库对应 Release 的 APK 附件，大小与 SHA-256 必须来自真实文件。
4. 将 APK、`update.json` 和校验文件上传至 Release 草稿，核对后发布为正式版并设为 Latest。

普通源码推送不会自动生成或发布签名 APK。当前发布方式及命令见 [在线更新发布说明](distribution/ONLINE_UPDATE.md)。

设备需能够访问 GitHub 及其 Release 下载服务；连接失败可从发布页手动下载。应用不包含 GitHub 令牌，也不提供静默安装。
