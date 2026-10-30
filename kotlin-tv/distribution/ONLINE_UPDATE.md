# 在线更新清单部署

原生 TV 客户端只读取固定地址：

`https://api.laozhangone.top/apk/update.json`

清单里的 `apk.url` 必须继续使用同一 HTTPS 域名下的 `/apk/` 路径，安装包和清单放在同一个目录。例如最终 0.10.8 包：

```json
{
  "schema": 1,
  "packageName": "com.hongguotv.laozhangapp",
  "versionCode": 25,
  "versionName": "0.10.8",
  "minSdk": 26,
  "apk": {
    "url": "https://api.laozhangone.top/apk/hongguoTV-laozhang-v0.10.8.apk",
    "name": "hongguoTV-laozhang-v0.10.8.apk",
    "size": 12345678,
    "sha256": "安装包的 64 位小写 SHA-256"
  },
  "notes": "本次更新内容"
}
```

在仓库根目录运行本地清单生成脚本即可根据 APK 自动计算长度和 SHA-256：

```powershell
python kotlin-tv/distribution/generate-online-update.py `
  outputs/hongguoTV-laozhang-v0.10.8.apk `
  --version-code 25 --version-name 0.10.8 --min-sdk 26 `
  --notes "修复搜索与在线更新" `
  --output outputs/update.json
```

然后把同一目录中的 `update.json` 和 APK 上传到服务器的 `/apk/` 目录，并确认公网 HTTPS 请求返回 `200`、JSON 的 `Content-Type` 正常，APK 下载大小与清单一致。脚本不会签名或上传文件，也不会替换原有 GitHub 发布脚本。

客户端会检查包名、版本号、最低 API、文件名、同域地址、文件长度、SHA-256，以及 APK 签名是否和当前安装版本一致。自动检查只在应用前台且未播放时进行；发现新版只显示提醒，必须点击“下载新版”，下载完成后再点击“安装新版”。播放期间不会弹窗、下载或安装。设置中的“版本与更新”可随时手动检查。

服务端目前若尚未部署该路径，客户端会显示“无法检查更新”；上传清单和 APK 后才会生效。
