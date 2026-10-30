# 红果TV 原生工程

当前客户端采用 Kotlin、Android 原生 View 与 Media3，最低 Android 8.0（API 26），包名为 `com.hongguotv.laozhangapp`。运行和构建原生 APK 均不需要 Node、React Native 或 Docker。

[下载正式 APK](https://github.com/laozhangaa/HongGuoTV/releases/latest) · [应用更新说明](AUTO_UPDATES.md) · [发布清单与流程](distribution/ONLINE_UPDATE.md)

## 开发环境

- JDK 17。
- Android SDK Platform 36、Build Tools 36.0.0。
- 工程自带 Gradle Wrapper。

设置 `ANDROID_HOME`，或在本目录创建被 Git 忽略的 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Windows 路径可写为 `D:/Android/SDK`。下列命令从仓库根目录运行；Windows 将 `./kotlin-tv/gradlew` 换为 `.\kotlin-tv\gradlew.bat`。

## 构建与验证

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:lintDebug :app:assembleDebug
adb install -r kotlin-tv/app/build/outputs/apk/debug/app-debug.apk
```

Debug 包使用开发签名，不能覆盖使用其他签名安装的正式版。发布前应完成核心测试、Release lint、签名校验和目标设备上的安装、遥控器及播放验证；具体结果以相应 Release 说明为准。

## 正式签名

正式构建从以下环境变量读取签名配置，也可使用被 Git 忽略的 `kotlin-tv/keystore.properties`。密钥路径建议使用绝对路径。

| 环境变量 | properties 字段 |
| --- | --- |
| `HONGGUOTV_KEYSTORE` | `storeFile` |
| `HONGGUOTV_STORE_PASSWORD` | `storePassword` |
| `HONGGUOTV_KEY_ALIAS` | `keyAlias` |
| `HONGGUOTV_KEY_PASSWORD` | `keyPassword` |

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:lintRelease :app:assembleRelease
```

输出文件为 `kotlin-tv/app/build/outputs/apk/release/app-release.apk`。版本默认从 `app/build.gradle.kts` 读取，也可通过 `HONGGUOTV_VERSION_CODE` 和 `HONGGUOTV_VERSION_NAME` 指定。

正式 APK、更新清单和发布源码必须对应同一次构建。继续更新已安装的正式版需使用原发布密钥；自行签名的构建不能覆盖其他签名的安装包。签名私钥和口令不提交到仓库。

## 工程结构

| 目录 | 内容 |
| --- | --- |
| `app/` | 电视界面、焦点与动效、本机记录、账号管理、Media3 播放器和 Android 安装流程 |
| `core/` | 内容检索、账号接口、媒体读取、手机网页遥控、更新元数据与 JVM 测试 |
| `distribution/` | 更新清单生成工具与发布说明 |
| `tools/` | 开发期对照样本和验证辅助工具 |

手机遥控由 APK 在局域网内提供网页，使用电视显示的地址或二维码访问。账号登录信息保存在设备中，与本地片单备份分开处理。

视频通过 HTTPS 分段读取，由本机处理后交给 Media3。下一集预载受缓冲、网络和自动连播设置约束；未完成或不可用时回到正常加载流程。清晰度受片源与设备解码能力限制，最低 API 配置不代表每种电视芯片都已验收。

仓库根目录保留的 React Native、Node 服务与早期文档属于历史工程，不是当前原生 APK 的运行依赖。

代码按 [GPL-3.0](LICENSE) 发布，来源与依赖许可见 [NOTICE.md](NOTICE.md)。
