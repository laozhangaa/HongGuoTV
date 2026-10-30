#!/usr/bin/env python3
"""Generate the first-party update.json beside a release APK.

This script only writes metadata. Upload the generated update.json and the APK
to https://api.laozhangone.top/apk/ after checking the hash and signature.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlparse

HOST = "api.laozhangone.top"
PREFIX = "/apk/"
PACKAGE = "com.hongguotv.laozhangapp"


def main():
    parser = argparse.ArgumentParser(description="生成红果TV在线更新清单")
    parser.add_argument("apk", type=Path, help="已签名 APK 路径")
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--min-sdk", type=int, default=26)
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--apk-url", help="完整 HTTPS 地址；默认使用同名 /apk/ 地址")
    parser.add_argument("--notes", default="")
    parser.add_argument("--output", type=Path, help="清单输出路径，默认写入 APK 同目录 update.json")
    args = parser.parse_args()

    apk = args.apk.resolve()
    if not apk.is_file() or apk.suffix.lower() != ".apk":
        parser.error("apk 必须是存在的 .apk 文件")
    if args.version_code < 1 or args.version_code > 2_147_483_647:
        parser.error("version-code 必须是有效的 Android versionCode")
    if not re.fullmatch(r"[0-9][0-9A-Za-z.+-]{0,63}", args.version_name):
        parser.error("version-name 格式不正确")
    if not 26 <= args.min_sdk <= 100:
        parser.error("min-sdk 必须在 26—100 之间")
    name = apk.name
    url = args.apk_url or f"https://{HOST}{PREFIX}{name}"
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.hostname != HOST or parsed.port not in (None, 443) or not parsed.path.startswith(PREFIX) or parsed.query or parsed.fragment or parsed.username:
        parser.error("apk-url 必须是 https://api.laozhangone.top/apk/ 下的地址")
    if Path(parsed.path).name != name:
        parser.error("apk-url 最后文件名必须与 APK 文件名一致")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+\-]{0,159}\.apk", name):
        parser.error("APK 文件名只能包含 ASCII 字母、数字、点、下划线、加号和连字符")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    manifest = {
        "schema": 1,
        "packageName": args.package,
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "minSdk": args.min_sdk,
        "apk": {"url": url, "name": name, "size": apk.stat().st_size, "sha256": digest},
        "notes": args.notes[:1500],
    }
    output = (args.output or apk.with_name("update.json")).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"manifest": str(output), "apk": name, "size": apk.stat().st_size, "sha256": digest}, ensure_ascii=False))


if __name__ == "__main__":
    main()
