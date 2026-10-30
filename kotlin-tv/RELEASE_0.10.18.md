# 红果TV 0.10.18

版本 0.10.18 / versionCode 35，包名 `com.hongguotv.laozhangapp`，最低 Android 8.0（API 26），沿用既有正式签名。

## 更新

- 应用内更新迁移至公开仓库 [laozhangaa/HongGuoTV Releases](https://github.com/laozhangaa/HongGuoTV/releases)。
- 从最新正式版的 `update.json` 获取版本与 APK 信息，兼容 GitHub 到 Release 下载服务的 HTTPS 重定向。
- 清单只接受本仓库的固定 APK 附件地址；继续校验文件大小、SHA-256、包名、版本和安装签名。
- 更新清单生成工具同步使用本仓库的 Release 地址。
- 重写项目主页及构建、更新文档，说明当前原生客户端的功能和使用方式。

## 安装与迁移

v0.10.17 及更早版本仍使用旧更新地址，需要手动下载安装一次本版。此后可在应用内检查本仓库发布的新版。

同包名、同签名覆盖安装保留本机数据。自动检查只提示，不自动下载；播放中不弹更新提示。用户确认下载后，仍需确认系统安装。

## 验证

- 更新模块的 13 项 JUnit 测试通过，覆盖版本比较、同仓库 APK 地址、GitHub 重定向规则、下载大小与摘要校验、下载中断及替换已有文件。
- Release lint 为 0 errors、41 warnings，签名 Release APK 已构建。
- 本机 Gradle 测试进程出现 JUnit 类加载错误，已直接运行同一批编译后的 `AppUpdatesTest` 完成上述验证；不将该环境错误计为业务测试通过。
- 更新清单与 APK 的匿名下载、大小、摘要及最终发布附件需在发布时核对；文件校验值随 Release 附件提供。

发布入口：[最新正式版](https://github.com/laozhangaa/HongGuoTV/releases/latest)。
