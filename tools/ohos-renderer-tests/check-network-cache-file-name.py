#!/usr/bin/env python3
"""Fail closed if KRNetworkCacheUtil can derive an unbounded cache file name
from a url, or can drop a FetchFileByDownloadOrCache callback on an
fs.access error.

Long signed image urls used to become `<url with punctuation replaced>.<ext>`
with no length limit, so fs.access / downloadFile failed with ENAMETOOLONG
(13900030); and because the access-error branch reported through
handleLoadFailure before any callback was registered in downloadTasks, the
caller never heard back and the image stayed on its placeholder.

Usage:
  check-network-cache-file-name.py            # check the working tree
  check-network-cache-file-name.py --self-test
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

SOURCE = Path("core-render-ohos/src/main/ets/utils/KRNetworkCacheUtil.ets")


def strip_comments(source: str) -> str:
    source = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), source, flags=re.S)
    return re.sub(r"//[^\n]*", "", source)


def compact(source: str) -> str:
    return re.sub(r"\s+", "", strip_comments(source))


def block_after(source: str, marker: str) -> str:
    clean = strip_comments(source)
    start = clean.find(marker)
    if start < 0:
        raise AssertionError(f"missing required function/method: {marker}")
    brace = clean.find("{", start)
    depth = 0
    for index in range(brace, len(clean)):
        if clean[index] == "{":
            depth += 1
        elif clean[index] == "}":
            depth -= 1
            if depth == 0:
                return clean[brace + 1 : index]
    raise AssertionError("unterminated block body")


def check(source: str) -> None:
    name = compact(block_after(source, "static urlToFilename(url: string): string"))
    if "returnKRNetworkCacheUtil.sha256Hex(url);" not in name:
        raise AssertionError("urlToFilename must return a fixed-length digest of the whole url")
    if ".replace(" in name:
        raise AssertionError("urlToFilename must not derive the file name from the url text")

    digest = compact(block_after(source, "private static sha256Hex(text: string): string"))
    if "cryptoFramework.createMd('SHA256')" not in digest or "digestSync()" not in digest:
        raise AssertionError("sha256Hex must compute a SHA-256 digest synchronously")
    if "newutil.TextEncoder().encodeInto(text)" not in digest or "buffer" in digest:
        raise AssertionError(
            "sha256Hex must feed the digest from TextEncoder, not a @ohos.buffer view "
            "(Buffer.buffer + byteOffset threw RangeError on device)"
        )

    fetch_head = compact(block_after(source, "static fetchFileByDownloadOrCache("))
    if not re.search(r"try\{cacheFilePath=KRNetworkCacheUtil\.generateCacheFilePathByUrl\(context,url\)\}catch\(e\)\{.*?callback\(null\)return\}", fetch_head):
        raise AssertionError("fetchFileByDownloadOrCache must answer callback(null) if deriving the cache path throws")

    ext = compact(block_after(source, "static  getPathExtension(url: string): string | null"))
    if "/^[a-zA-Z0-9]{1,16}$/.test(ext)" not in ext:
        raise AssertionError("getPathExtension must bound the extension to a short alphanumeric token")

    fetch = compact(block_after(source, "static fetchFileByDownloadOrCache("))
    access_err = re.search(r"fs\.access\(cacheFilePath,\(err,res\)=>\{if\(err\)\{(.*?)\}else\{", fetch)
    if access_err is None:
        raise AssertionError("fetchFileByDownloadOrCache must branch on the fs.access error")
    if "callback(null);" not in access_err.group(1):
        raise AssertionError("an fs.access error must complete the request with callback(null)")
    if "handleLoadFailure(" in access_err.group(1):
        raise AssertionError("the fs.access error branch must not report through downloadTasks (nothing is registered yet)")


def self_test(source: str) -> None:
    check(source)
    mutations = {
        "url text as file name": source.replace(
            "return KRNetworkCacheUtil.sha256Hex(url);",
            "return url.replace(/[^a-zA-Z0-9_-]/g, '_');",
        ),
        "unbounded extension": source.replace("/^[a-zA-Z0-9]{1,16}$/.test(ext)", "ext.length > 0"),
        "buffer view for the digest": source.replace(
            "md.updateSync({ data: new util.TextEncoder().encodeInto(text) });",
            "const bytes = buffer.from(text, 'utf-8');\n    md.updateSync({ data: new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.length) });",
        ),
        "escaping cache path error": source.replace("      callback(null)\n      return\n    }", "      throw e\n    }"),
        "silent access failure": source.replace(
            "            callback(null);\n          } else {",
            "            KRNetworkCacheUtil.handleLoadFailure(url, cacheFilePath);\n          } else {",
        ),
    }
    for label, mutated in mutations.items():
        if mutated == source:
            raise AssertionError(f"self-test mutation did not apply: {label}")
        try:
            check(mutated)
        except AssertionError:
            continue
        raise AssertionError(f"check accepted a broken source: {label}")
    print("check-network-cache-file-name: self-test OK")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--source", type=Path, default=SOURCE)
    args = parser.parse_args()
    source = args.source.read_text(encoding="utf-8")
    if args.self_test:
        self_test(source)
    else:
        check(source)
        print("check-network-cache-file-name: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
