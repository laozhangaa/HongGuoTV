# 红果TV 0.10.15

版本 0.10.15 / versionCode 32，包名 `com.hongguotv.laozhangapp`，最低 API 26，沿用 tudou 签名。

## 账号提示美化

- 移除账号操作使用的系统白色 Toast。
- 新增统一的右上角深色反馈浮层，使用页面安全边距、圆角、细边框和红色加载指示。
- 连接账号时显示非阻塞加载提示，完成后撤销；普通结果提示按可访问性推荐时长自动消失。
- 提示窗口 `NOT_FOCUSABLE`、`NOT_TOUCHABLE`，不抢电视遥控焦点；离开页面、退出账号和销毁 Activity 时自动清理。
- 启动恢复已保存账号时静默同步，不再打开首页就弹出连接提示或失败 Toast。
- 账号模态面板内触发刷新时，提示显示在面板上层，避免被遮挡。

## 验证

Release 构建与 `lintRelease` 通过（0 errors、41 warnings，均为既有类型/弃用提示）。已使用 MuMu Android 15 横屏设备安装验证：账号面板刷新时显示右上角深色加载浮层，DPAD 操作仍能移动焦点，完成后提示收起。设备随后断开无线 ADB，未进行额外数据清理。

APK 已用现有 tudou 签名签署并通过 `apksigner verify`；SHA-256 记录在 `outputs/SHA256SUMS-v0.10.15`。更新清单为 `outputs/update.json` 和 `outputs/update-v0.10.15.json`。APK 与清单尚未上传服务器。

修改前备份：`backups/before-account-notice-20261029/`。
