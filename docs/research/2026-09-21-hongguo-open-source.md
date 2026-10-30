# 已采用的开源接口来源记录

原调研日期：2026-09-21。本文记录本项目实际采用的开源来源与验证范围。

## drpys

- 项目：[woshishiq1/drpys](https://github.com/woshishiq1/drpys)。
- 固定源码：[红果果[短].js](https://github.com/woshishiq1/drpys/blob/22261adfa31435e3b3ef5a730b8a2a75bcaf1715/spider/js/%E7%BA%A2%E6%9E%9C%E6%9E%9C%5B%E7%9F%AD%5D.js)。
- 对应提交：`22261adfa31435e3b3ef5a730b8a2a75bcaf1715`；许可证为 GPL-3.0。
- 本地对照源为 `server/vendor/hongguo.cjs`，原生客户端的部分协议与媒体处理逻辑由其移植。
- 来源、移植范围和依赖许可分别记录于 [Node 服务第三方声明](../../server/vendor/NOTICE.md) 与 [原生版第三方声明](../../kotlin-tv/NOTICE.md)。

## 验证范围

原调研仅核对公开仓库目录、关键源码及许可证元数据，未运行候选实现或验证真实播放。历史源码中的接口和设备参数不能作为当前平台兼容性的保证；后续实际验证以本仓库各版本发布记录为准。
