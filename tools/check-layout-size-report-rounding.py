#!/usr/bin/env python3
"""Lock true two-decimal ceiling and legacy null serialization across renderers."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


ANDROID_CORE = Path(
    "core-render-android/src/main/java/com/tencent/kuikly/core/render/android/core/KuiklyRenderCore.kt"
)
ANDROID_FORMATTER = ANDROID_CORE.with_name("LayoutSizeFormatter.kt")
ANDROID_RICH_TEXT = Path(
    "core-render-android/src/main/java/com/tencent/kuikly/core/render/android/"
    "expand/component/KRRichTextView.kt"
)
WEB_CORE = Path(
    "core-render-web/base/src/jsMain/kotlin/com/tencent/kuikly/core/render/web/core/KuiklyRenderCore.kt"
)
WEB_FORMATTER = WEB_CORE.with_name("LayoutSizeFormatter.kt")
IOS_CONVERT = Path("core-render-ios/Extension/Category/KRConvertUtil.m")
IOS_FORMATTER = IOS_CONVERT.with_name("KRLayoutSizeFormatter.h")
OHOS_CONVERT = Path("core-render-ohos/src/main/cpp/libohos_render/utils/KRConvertUtil.cpp")
OHOS_FORMATTER = OHOS_CONVERT.with_name("KRLayoutSizeFormatter.h")

PRODUCTION_FILES = (
    ANDROID_CORE,
    ANDROID_FORMATTER,
    ANDROID_RICH_TEXT,
    WEB_CORE,
    WEB_FORMATTER,
    IOS_CONVERT,
    IOS_FORMATTER,
    OHOS_CONVERT,
    OHOS_FORMATTER,
)

REQUIRED = {
    ANDROID_CORE: ("formatLayoutSizeForReport(size?.width, size?.height)",),
    ANDROID_FORMATTER: (
        "Math.ulp(value).toDouble() * (HUNDREDTH_SCALE / 2.0)",
        "ceil(normalized) / HUNDREDTH_SCALE",
        'return "0.00|0.00"',
    ),
    ANDROID_RICH_TEXT: (
        "layoutParamsWidthIsUnresolved(width: Int): Boolean = width <= 0",
        "layoutParamsWidthIsUnresolved(params.width)",
    ),
    WEB_CORE: ("formatLayoutSizeForReport(size?.width, size?.height)",),
    WEB_FORMATTER: (
        "floatUlp(value) * (HUNDREDTH_SCALE / 2.0)",
        "ceil(normalized) / HUNDREDTH_SCALE",
        'return "0.00|0.00"',
    ),
    IOS_CONVERT: (
        "KRCeilLayoutSizeToHundredth(size.width)",
        "KRCeilLayoutSizeToHundredth(size.height)",
    ),
    IOS_FORMATTER: (
        "representation_tolerance",
        "return ceil(scaled) / scale;",
    ),
    OHOS_CONVERT: (
        "CeilLayoutSizeToHundredth(size.width)",
        "CeilLayoutSizeToHundredth(size.height)",
    ),
    OHOS_FORMATTER: (
        "representation_tolerance",
        "return std::ceil(scaled) / scale;",
    ),
}

BIAS = re.compile(r"\+\s*0\.005(?:f)?\b")


def load_sources() -> dict[Path, str]:
    return {path: path.read_text(encoding="utf-8") for path in PRODUCTION_FILES}


def check(sources: dict[Path, str]) -> None:
    for path, snippets in REQUIRED.items():
        source = sources[path]
        for snippet in snippets:
            if snippet not in source:
                raise AssertionError(f"{path}: missing required layout-size contract: {snippet}")
        if BIAS.search(source):
            raise AssertionError(f"{path}: half-cent bias is not a true ceiling operation")


def expect_failure(sources: dict[Path, str], path: Path, old: str, new: str, label: str) -> None:
    mutated = dict(sources)
    if old not in mutated[path]:
        raise AssertionError(f"self-test setup missing marker for {label}")
    mutated[path] = mutated[path].replace(old, new, 1)
    try:
        check(mutated)
    except AssertionError:
        return
    raise AssertionError(f"self-test mutant survived: {label}")


def self_test(sources: dict[Path, str]) -> None:
    check(sources)
    expect_failure(
        sources,
        ANDROID_CORE,
        "formatLayoutSizeForReport(size?.width, size?.height)",
        'String.format(java.util.Locale.ENGLISH, "%.2f|%.2f", size!!.width + 0.005f, size.height + 0.005f)',
        "Android biased formatter",
    )
    expect_failure(
        sources,
        WEB_CORE,
        "formatLayoutSizeForReport(size?.width, size?.height)",
        '(size!!.width + 0.005f).asDynamic().toFixed(2)',
        "Web biased formatter",
    )
    expect_failure(
        sources,
        IOS_CONVERT,
        "KRCeilLayoutSizeToHundredth(size.width)",
        "size.width + 0.005",
        "iOS biased formatter",
    )
    expect_failure(
        sources,
        OHOS_CONVERT,
        "CeilLayoutSizeToHundredth(size.width)",
        "size.width + 0.005",
        "OHOS biased formatter",
    )
    expect_failure(
        sources,
        ANDROID_FORMATTER,
        'return "0.00|0.00"',
        'return "0|0"',
        "Android null serialization drift",
    )
    expect_failure(
        sources,
        ANDROID_RICH_TEXT,
        "layoutParamsWidthIsUnresolved(width: Int): Boolean = width <= 0",
        "layoutParamsWidthIsUnresolved(width: Int): Boolean = width == 0",
        "Android rich-text unresolved width narrowing",
    )
    expect_failure(
        sources,
        IOS_FORMATTER,
        "return ceil(scaled) / scale;",
        "return round(scaled) / scale;",
        "iOS nearest rounding",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    sources = load_sources()
    self_test(sources) if args.self_test else check(sources)
    print("layout_size_report_rounding=pass")


if __name__ == "__main__":
    main()
