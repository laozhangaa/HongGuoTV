#!/usr/bin/env python3
"""Generate update.json for a signed APK in HongGuoTV's GitHub Releases.

This script only writes metadata. Upload the generated update.json and the APK
to the same GitHub Release after checking the hash and signature.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlparse

HOST = "github.com"
PREFIX = "/laozhangaa/HongGuoTV/releases/download/"
PACKAGE = "com.hongguotv.laozhangapp"


def main():
    parser = argparse.ArgumentParser(description="生成红果TV在线更新清单")
    parser.add_argument("apk", type=Path, help="已签名 APK 路径")
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--min-sdk", type=int, default=26)
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--apk-url", help="本仓库 Release APK 地址；默认使用 v<version-name> 标签下同名 APK")
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
    url = args.apk_url or f"https://{HOST}{PREFIX}v{args.version_name}/{name}"
    parsed = urlparse(url)
    release_path = re.fullmatch(re.escape(PREFIX) + r"[A-Za-z0-9][A-Za-z0-9._+\-]{0,127}/[A-Za-z0-9][A-Za-z0-9._+\-]{0,159}\.apk", parsed.path)
    if parsed.scheme != "https" or parsed.hostname != HOST or parsed.port not in (None, 443) or not release_path or parsed.query or parsed.fragment or parsed.username or parsed.password:
        parser.error("apk-url 必须是本仓库 GitHub Releases 的 APK 地址")
    if Path(parsed.path).name != name:
        parser.error("apk-url 最后文件名必须与 APK 文件名一致")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+\-]{0,159}\.apk", name):
        parser.error("APK 文件名只能包含 ASCII 字母、数字、点、下划线、加号和连字符")
    if args.package != PACKAGE:
        parser.error(f"package 必须是 {PACKAGE}")
    if not 1 <= apk.stat().st_size <= 80 * 1024 * 1024:
        parser.error("APK 大小必须在 1 字节至 80 MiB 之间")
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
