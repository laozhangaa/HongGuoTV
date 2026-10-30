# Releases 更新清单与发布

应用从本仓库最新正式 Release 读取更新清单：

```text
https://github.com/laozhangaa/HongGuoTV/releases/latest/download/update.json
```

每个正式 Release 应包含已签名 APK、`update.json` 和校验文件。APK 固定使用对应版本的附件地址，例如：

```text
https://github.com/laozhangaa/HongGuoTV/releases/download/v0.10.18/hongguoTV-laozhang-v0.10.18.apk
```

不要把 Release 网页地址或下载重定向后的临时地址填入清单。

## 生成清单

在仓库根目录运行，APK 路径为第一个位置参数：

```sh
python kotlin-tv/distribution/generate-online-update.py \
  outputs/hongguoTV-laozhang-v0.10.18.apk \
  --apk-url https://github.com/laozhangaa/HongGuoTV/releases/download/v0.10.18/hongguoTV-laozhang-v0.10.18.apk \
  --version-code 35 \
  --version-name 0.10.18 \
  --notes "在线更新迁移至本仓库 Releases。" \
  --output outputs/update.json
```

脚本根据实际文件计算大小和 SHA-256，不签名、不构建、不上传。省略 `--apk-url` 时，默认使用本仓库 `v<version-name>` 标签下的同名 APK 附件地址。包名默认为 `com.hongguotv.laozhangapp`，最低 API 默认为 26；版本参数必须与 APK 内部元数据一致。

清单格式为 `schema: 1`，包含 `packageName`、`versionCode`、`versionName`、`minSdk`、`notes`，以及 `apk` 中的 `url`、`name`、`size`、`sha256`。

## 发布步骤

1. 提交对应源码，完成测试、Release lint、签名构建和设备验证，核对 APK 的包名、版本、最低系统和签名。
2. 生成清单和校验文件，确认文件名、大小和 SHA-256 与待上传 APK 一致。
3. 创建指向准确源码提交的 Release 草稿，上传附件。
4. 核对 GitHub 上附件的大小和 SHA-256，再发布为正式版并设为 Latest。
5. 不登录 GitHub 访问固定清单入口，并验证 APK 可下载且校验通过；在旧版客户端上确认更新提示、下载和覆盖安装。

使用 GitHub CLI 的示例（将 `COMMIT_SHA` 替换为本次发布源码提交，先准备版本说明及校验文件）：

```sh
gh release create v0.10.18 --repo laozhangaa/HongGuoTV \
  --target COMMIT_SHA --draft --title "红果TV v0.10.18" \
  --notes-file outputs/release-notes.md

gh release upload v0.10.18 --repo laozhangaa/HongGuoTV \
  outputs/hongguoTV-laozhang-v0.10.18.apk \
  outputs/update.json outputs/SHA256SUMS-v0.10.18

# 附件核验通过后发布
gh release edit v0.10.18 --repo laozhangaa/HongGuoTV --draft=false --latest
```

后续发布需递增实际 APK 的 `versionCode`，并更新版本名称、标签及文件名。已发布附件不用于伪造更高版本；首次迁移旧更新渠道时，用户需手动安装 v0.10.18 或更高版本。
