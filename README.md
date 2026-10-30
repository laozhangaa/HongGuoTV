# 红果TV · HongGuoTV

本仓库保存自用版 **Kotlin 原生独立客户端**，当前源码版本 **0.10.17 / versionCode 34**。安装后联网即可使用，无需 Node / Docker。最低 Android 8.0，支持电视遥控器和手机网页遥控。

**[原生版说明与构建](kotlin-tv/README.md)** · [在线更新清单发布方法](kotlin-tv/distribution/ONLINE_UPDATE.md) · [0.10.17 交付说明](kotlin-tv/RELEASE_0.10.17.md)

源码仓库为私有的 `laozhangaa/HongGuoTV`。在线更新暂时保持现有地址 `https://api.laozhangone.top/apk/update.json`，后续渠道另行确定。普通源码推送不会发布安装包。

电视在前台浏览时检查新版并提示，播放中不打扰；下载和安装由用户确认，覆盖安装保留本机记录。清单必须记录实际 APK 的包名、版本、大小和 SHA-256，安装时还会核对既有签名。

支持漫剧与短剧浏览、剧库筛选、搜索、排行榜、短剧预约、收藏、观看历史、账号管理、倍速和选集，以及手机端搜索与播放控制。播放器使用 Media3 1.11.1，保留下一集预载和 IPv4 优先连接策略。

构建需要 JDK 17、Android SDK 36。先配置本机 `kotlin-tv/local.properties`，然后运行：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:assembleDebug
```

正式签名通过本机 `keystore.properties` 或 `HONGGUOTV_*` 环境变量提供。仓库不包含签名私钥、本机 SDK 路径、账号数据、编译缓存或本地安装包。

本项目基于原红果TV工程继续修改，保留 GPL-3.0 许可证及相关第三方署名。历史 React Native / Node 服务代码保留以便查阅，不是当前原生 APK 的运行依赖。

原生工程位于 `kotlin-tv/`，包名 `com.hongguotv.laozhangapp`，可与旧版并存。以下为保留的 React Native 版说明。

---

用于自家电视的非官方红果短剧客户端。首版采用 **React Native TV + TypeScript**，最低 Android 8.0（API 26）。电视负责界面和播放，独立 Node 服务负责内容接入与媒体分段处理，便于后续其他平台复用。

**下载：[v0.1.1 APK、源码及 Docker 部署包](https://github.com/N3urda/hongguoTV/releases/tag/v0.1.1)** · [Docker 部署指南](docs/DOCKER.md)

## 当前功能

- 推荐、关键词搜索、分页、详情和选集。
- 遥控器焦点、方向键、确定键和返回键操作。
- 原比例播放、暂停、前后跳转、上下集及自动连播。
- 本地收藏、最近观看、断点续播。
- 服务地址 / 可选访问口令设置，连接检测及播放失败重试。

v0.1.1 改善 TV 焦点滚动、返回恢复、遥控器播放按键、安全边距与选集分组，见 [本版交付说明](docs/RELEASE_0.1.1.md)。Android 8.0 的最低版本配置不等于目标电视已经验收；Apple TV 工程骨架保留，移动端和 Web 还未交付。

## 在电视上使用

1. 在电脑或 NAS 安装 Node.js 22 或更新版本，在本目录运行：

   ```sh
   npm ci
   npm run bridge -- --lan
   ```

2. 从 Release 下载 APK，拷贝到电视安装。本地构建产物为 `outputs/hongguotv-0.1.1-android8.apk`，也可以通过已连接的 ADB 安装：

   ```sh
   adb install -r outputs/hongguotv-0.1.1-android8.apk
   ```

3. 电视和服务所在设备连接同一家庭网络。在应用「设置」中输入启动日志显示的 `http://电脑局域网IP:8787`，选择「连接并保存」。电视上不能把 `127.0.0.1` 当成电脑地址。
4. 进入推荐或搜索，打开剧集开始播放。观看期间内容服务需保持运行，电脑不能休眠。

首次启动默认没有内容服务地址，也不会用测试数据冒充真实内容。「测试播放器」仅播放明确标注的 Big Buck Bunny 公开样片。

服务默认仅监听 `127.0.0.1`；`--lan` 显式开放家庭网络访问。可通过 `BRIDGE_TOKEN` 设置访问口令，并在电视端填入相同值。此服务按家庭网络使用设计，未实现公网部署所需的完整访问控制。手机 / 浏览器不要假定可直接使用原生端的媒体和请求头配置。

## 独立服务部署

只部署服务时不需要安装 React Native 依赖：

```sh
npm ci --prefix server
node server/index.mjs --lan
```

电脑 / NAS 可使用 Docker，从源码或 Release 的 Docker 部署包根目录执行：

```sh
cp .env.example .env
docker compose --env-file .env -f server/compose.yaml up -d --build
```

也提供 `ghcr.io/n3urda/hongguotv-bridge:0.1.1` 的 AMD64 / ARM64 镜像，以及根目录的镜像部署 Compose；私有镜像登录、更新和排查见 [Docker 部署指南](docs/DOCKER.md)。服务参数为 `BRIDGE_HOST`、`PORT`、`BRIDGE_TOKEN`；Node 进程不会自动读取 `.env`，应由 shell 或部署工具传入环境变量。

## 开发与验证

```sh
npm ci
npm run typecheck
npm test
npm run bridge
# 另一个终端：访问真实平台，结果写入 outputs/probe.json
npm run probe
# 需要 JDK 17、Android SDK 36、Build Tools 36.0.0、NDK 27.1.12297006
npm run build:android
```

`build:android` 生成包含 JavaScript 的 release APK，运行时不需要 Metro。首版使用本地开发签名供自用安装；签名文件被 Git 忽略，换机器构建需保留同一密钥才能直接覆盖已有安装。对外发布前应配置自己的正式签名。

服务 API 与部署边界见 [接口说明](docs/API.md)。

## 项目资料

- [v0.1.1 交付与验收](docs/RELEASE_0.1.1.md)
- [首版交付与验收](docs/FIRST_RELEASE.md)
- [Docker 部署](docs/DOCKER.md)
- [项目状态](docs/PROJECT_STATUS.md)
- [React Native 与多平台架构](docs/ARCHITECTURE.md)
- [接口研究](docs/research/2026-09-21-hongguo-open-source.md)

## 来源与许可证

项目与红果官方无关联。内容接口为非官方实现，平台变化可能导致失效；错误会明确显示，不会替换为虚假内容。

Node 服务使用固定提交的 `drpys` 源，保留原文件、GPL-3.0 许可证和来源记录，见 [第三方说明](server/vendor/NOTICE.md)。Android / tvOS 工程骨架源于 MIT 许可的 RN TV 模板。本项目代码按 [GPL-3.0](LICENSE) 提供，第三方部分保留各自许可。
