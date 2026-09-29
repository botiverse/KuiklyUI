#!/usr/bin/env python3
"""Verify that every C entry point the Kotlin/Native side imports from
libkuikly.so is actually exported by the built library.

core-render-ohos compiles with -fvisibility=hidden when KUIKLY_OHOS_SIZE_OPTS
is on (the release default), so a `com_tencent_kuikly_*` function that is not
declared with default visibility silently disappears from the dynamic symbol
table. libshared.so then resolves the PLT slot to garbage and the first call
crashes the kuikly thread with SIGSEGV. This check turns that into a build
failure.

Usage:
  check-cinterop-exports.py --def core/src/ohosArm64Main/ohosInterop/cinterop/ohos.def \
      --so path/to/libkuikly.so [--nm llvm-nm]
  check-cinterop-exports.py --self-test
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

EXTERN_RE = re.compile(r"^\s*extern\s+[^;]*?\b(com_tencent_kuikly_[A-Za-z0-9_]+)\s*\(", re.M)


def imported_symbols(def_text: str) -> list[str]:
    seen: list[str] = []
    for name in EXTERN_RE.findall(def_text):
        if name not in seen:
            seen.append(name)
    return seen


def exported_symbols(nm_output: str) -> set[str]:
    names: set[str] = set()
    for line in nm_output.splitlines():
        parts = line.split()
        if len(parts) < 2:
            continue
        kind = parts[-2] if len(parts) >= 3 else parts[0]
        name = parts[-1]
        if kind in {"T", "W", "i"} or (len(parts) == 2 and parts[0] in {"T", "W", "i"}):
            names.add(name)
    return names


def run_nm(nm: str, so: Path) -> str:
    return subprocess.run(
        [nm, "-D", "--defined-only", str(so)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout


def find_nm(explicit: str | None) -> str:
    candidates = [explicit] if explicit else []
    sdk = os.environ.get("OHOS_SDK_HOME")
    if sdk:
        candidates.append(os.path.join(sdk, "native", "llvm", "bin", "llvm-nm"))
    candidates += ["llvm-nm", "nm"]
    for c in candidates:
        if c and (os.path.isfile(c) or shutil.which(c)):
            return c
    raise SystemExit("error: no llvm-nm/nm found; pass --nm")


def check(def_path: Path, so_path: Path, nm: str) -> int:
    wanted = imported_symbols(def_path.read_text(encoding="utf-8"))
    if not wanted:
        print(f"error: no com_tencent_kuikly_* externs found in {def_path}", file=sys.stderr)
        return 2
    have = exported_symbols(run_nm(nm, so_path))
    missing = [s for s in wanted if s not in have]
    for s in wanted:
        print(f"{'ok     ' if s in have else 'MISSING'} {s}")
    if missing:
        print(
            f"error: {so_path} does not export {len(missing)} symbol(s) imported by "
            f"{def_path}: {', '.join(missing)}. Declare them with "
            '__attribute__((visibility("default"))) (see KRRenderCValue.h).',
            file=sys.stderr,
        )
        return 1
    print(f"all {len(wanted)} cinterop entry points exported by {so_path.name}")
    return 0


def self_test() -> int:
    def_text = """
extern int com_tencent_kuikly_SetCallKotlin(CallKotlin callKotlin);
extern void com_tencent_kuikly_ScheduleContextTask(const char* pagerId,
        void (*onSchedule)(const char* pagerId));
extern void com_tencent_kuikly_ScheduleContextIdleTask(const char* pagerId, void (*onSchedule)(const char* pagerId));
long long com_tencent_kuikly_GetThreadCPUTimeInNanoseconds() { return 0; }
"""
    assert imported_symbols(def_text) == [
        "com_tencent_kuikly_SetCallKotlin",
        "com_tencent_kuikly_ScheduleContextTask",
        "com_tencent_kuikly_ScheduleContextIdleTask",
    ], imported_symbols(def_text)
    nm_good = """
0000000000083f00 T com_tencent_kuikly_SetCallKotlin
0000000000083f40 T com_tencent_kuikly_ScheduleContextTask
0000000000083f80 T com_tencent_kuikly_ScheduleContextIdleTask
                 w __cxa_finalize
"""
    nm_bad = """
0000000000083f00 T com_tencent_kuikly_SetCallKotlin
0000000000083f40 T com_tencent_kuikly_ScheduleContextTask
0000000000083f80 t com_tencent_kuikly_ScheduleContextIdleTask
"""
    good = exported_symbols(nm_good)
    bad = exported_symbols(nm_bad)
    assert "com_tencent_kuikly_ScheduleContextIdleTask" in good
    assert "com_tencent_kuikly_ScheduleContextIdleTask" not in bad, bad
    assert "__cxa_finalize" not in good
    # End-to-end through check() with a fake nm.
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        (d / "ohos.def").write_text(def_text, encoding="utf-8")
        (d / "libkuikly.so").write_bytes(b"")
        fake_nm = d / "fake-nm"
        fake_nm.write_text("#!/bin/sh\ncat \"$FAKE_NM_OUTPUT\"\n", encoding="utf-8")
        fake_nm.chmod(0o755)
        (d / "good.txt").write_text(nm_good, encoding="utf-8")
        (d / "bad.txt").write_text(nm_bad, encoding="utf-8")
        os.environ["FAKE_NM_OUTPUT"] = str(d / "good.txt")
        assert check(d / "ohos.def", d / "libkuikly.so", str(fake_nm)) == 0
        os.environ["FAKE_NM_OUTPUT"] = str(d / "bad.txt")
        assert check(d / "ohos.def", d / "libkuikly.so", str(fake_nm)) == 1
    print("check-cinterop-exports self-test ok")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--def", dest="def_path", type=Path, help="cinterop .def with the extern declarations")
    ap.add_argument("--so", dest="so_path", type=Path, help="built libkuikly.so")
    ap.add_argument("--nm", help="llvm-nm binary (default: $OHOS_SDK_HOME/native/llvm/bin/llvm-nm, then PATH)")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if not args.def_path or not args.so_path:
        ap.error("--def and --so are required unless --self-test")
    return check(args.def_path, args.so_path, find_nm(args.nm))


if __name__ == "__main__":
    sys.exit(main())
