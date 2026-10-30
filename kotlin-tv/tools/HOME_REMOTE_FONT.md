# 首页遥控面板标题字体

`app/src/main/res/font/home_remote_display.ttf` 是 Noto Serif SC 的 800 字重静态 TrueType 子集，仅包含“手机扫码，变身遥控器”的字形。文件由 Google Fonts 提供，大小约 5.5 KB，支持 Android 8.0 直接加载，不需要联网取字体。

来源 CSS：

`https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@800&text=手机扫码，变身遥控器`

请求时使用传统浏览器 User-Agent（例如 `Mozilla/4.0`），取得 `format('truetype')` 字体链接。修改标题文字时应同步重新生成字体子集，避免缺字。按钮、说明文字和剧名继续使用系统字体。

许可证：`app/src/main/assets/licenses/OFL-NotoSerifSC.txt`（SIL Open Font License 1.1）。
