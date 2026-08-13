#!/usr/bin/env python3
"""Publish one Kuikly release set like an ordinary immutable Maven release.

One repository secret authenticates every PUT. Product files are uploaded one
by one, exact existing bytes are treated as an idempotent retry, and a byte
conflict fails without overwrite. After every product has been read back from
the public Maven origin, the authorized release manifest is uploaded last.
Consumers only use that manifest; claim, lease, and multi-token protocols are
deliberately outside this contract.
"""
from __future__ import annotations

import argparse
import base64
import concurrent.futures
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Sequence

from kuikly_release_contract import (
    GROUP,
    MANIFEST_PATH,
    PUBLIC_MAVEN_ORIGIN,
    RELEASE,
    canonical_set_digest,
    checksum_descriptor,
    json_bytes,
    sha256_bytes,
    validate_manifest,
)


CONTROL_ORIGIN = "https://artifacts.botiverse.dev"
CONTROL_LIST_PATH = "/api/scopes/com.tencent.kuikly-open/artifacts"
PUBLISH_USERNAME = "raft-ci"
PUBLISH_TOKEN_ENV = "RAFT_ARTIFACTS_PUBLISH_TOKEN"
USER_AGENT = "kuikly-maven-publish/1.0"
MAX_PARALLEL_REQUESTS = 16


class PublishError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PublishError(message)


def canonical_origin(value: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise PublishError(f"invalid origin: {error}") from error
    require(parsed.scheme == "https", "origin must use HTTPS")
    require(parsed.hostname is not None, "origin lacks hostname")
    require(parsed.username is None and parsed.password is None, "origin must not contain credentials")
    require(parsed.path in {"", "/"} and not parsed.query and not parsed.fragment, "origin must not contain path/query/fragment")
    host = parsed.hostname.lower()
    netloc = host if port in {None, 443} else f"{host}:{port}"
    return urllib.parse.urlunsplit(("https", netloc, "", "", ""))


class RejectRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):  # type: ignore[override]
        del file_pointer, message, headers
        source = urllib.parse.urlsplit(request.full_url)
        target = urllib.parse.urlsplit(new_url)
        raise PublishError(
            "repository redirect rejected before another request: "
            f"HTTP {code}, {source.scheme}://{source.netloc} -> {target.scheme}://{target.netloc}"
        )


class Http:
    def __init__(self, opener: urllib.request.OpenerDirector | None = None) -> None:
        self.opener = opener or urllib.request.build_opener(RejectRedirect())

    def request(
        self,
        origin: str,
        path: str,
        method: str,
        *,
        body: bytes | None = None,
        token: str | None = None,
        content_type: str | None = None,
    ) -> tuple[int, bytes]:
        require(method in {"GET", "PUT"}, f"unsupported HTTP method: {method}")
        require((method == "PUT") == (body is not None), f"HTTP body mismatch for {method}")
        origin = canonical_origin(origin)
        require(path.startswith("/") and ".." not in path.split("/"), f"unsafe HTTP path: {path}")
        headers = {"User-Agent": USER_AGENT}
        if token is not None:
            require(token != "" and "\r" not in token and "\n" not in token, "publish token malformed")
            encoded = base64.b64encode(f"{PUBLISH_USERNAME}:{token}".encode("utf-8")).decode("ascii")
            headers["Authorization"] = f"Basic {encoded}"
        if content_type is not None:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(origin + path, data=body, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=60) as response:
                return response.status, response.read()
        except PublishError:
            raise
        except urllib.error.HTTPError as error:
            if 300 <= error.code <= 399:
                raise PublishError(f"redirect rejected before another request: HTTP {error.code}") from error
            return error.code, error.read() or b""
        except urllib.error.URLError as error:
            raise PublishError(f"transport error: {error.reason}") from error


def load_json(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PublishError(f"cannot read {label} {path}: {error}") from error
    require(isinstance(value, dict), f"{label} root must be an object")
    return value


def owned_prefixes(objects: Iterable[dict[str, Any]]) -> list[str]:
    prefixes = sorted({item["path"].rsplit("/", 1)[0] + "/" for item in objects})
    require(prefixes, "object set has no owned prefixes")
    return prefixes


def completion_manifest(manifest: dict[str, Any]) -> tuple[dict[str, Any], bytes]:
    final_manifest = json.loads(json.dumps(manifest))
    final_manifest["completionState"] = "complete"
    final_manifest["publishable"] = True
    return final_manifest, json_bytes(final_manifest)


def read_bundle(bundle: Path, relative: str, expected: dict[str, Any]) -> bytes:
    pure = PurePosixPath(relative)
    require(not pure.is_absolute() and ".." not in pure.parts and "\\" not in relative, f"unsafe bundle path: {relative}")
    path = bundle.joinpath(*pure.parts)
    require(path.is_file() and not path.is_symlink(), f"bundle byte missing: {relative}")
    body = path.read_bytes()
    require(len(body) == expected["size"], f"bundle size mismatch: {relative}")
    require(sha256_bytes(body) == expected["sha256"], f"bundle digest mismatch: {relative}")
    return body


def public_listing(http: Http) -> set[str]:
    status, body = http.request(CONTROL_ORIGIN, CONTROL_LIST_PATH, "GET")
    require(status == 200, f"public control-plane listing HTTP {status}")
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PublishError(f"public control-plane listing is not JSON: {error}") from error
    require(isinstance(payload, dict) and payload.get("scope") == GROUP, "public listing scope mismatch")
    artifacts = payload.get("artifacts")
    require(isinstance(artifacts, list), "public listing has no artifacts array")
    keys: set[str] = set()
    for item in artifacts:
        require(isinstance(item, dict) and isinstance(item.get("key"), str), "invalid public listing entry")
        key = item["key"]
        parts = key.split("/")
        require(
            parts[:3] == ["com", "tencent", "kuikly-open"]
            and len(parts) >= 5
            and all(part not in {"", ".", ".."} for part in parts)
            and "\\" not in key
            and "%" not in key
            and "\x00" not in key,
            f"unsafe/noncanonical public listing key: {key!r}",
        )
        require(key not in keys, f"duplicate public listing key: {key}")
        keys.add(key)
    return keys


def repository_path(relative: str) -> str:
    pure = PurePosixPath(relative)
    require(not pure.is_absolute() and ".." not in pure.parts and "\\" not in relative, f"unsafe repository path: {relative}")
    return "/" + urllib.parse.quote(relative, safe="/._-")


def public_get(http: Http, relative: str) -> tuple[int, bytes]:
    return http.request(PUBLIC_MAVEN_ORIGIN, repository_path(relative), "GET")


def completion_matches(remote: bytes, candidate: dict[str, Any]) -> bool:
    try:
        final_manifest, expected = completion_manifest(candidate)
        validate_manifest(final_manifest, require_publishable=True)
    except RuntimeError:
        return False
    return remote == expected


def classify(http: Http, manifest: dict[str, Any], bundle: Path) -> dict[str, Any]:
    validate_manifest(manifest)
    expected = {
        item["path"]: {"path": item["path"], "sha256": item["sha256"], "size": item["size"]}
        for item in manifest["publications"]
    }
    expected_paths = set(expected)
    prefixes = set(owned_prefixes(expected.values()))
    prefixes.add(MANIFEST_PATH.rsplit("/", 1)[0] + "/")
    listing = public_listing(http)
    remote_owned = {key for key in listing if any(key.startswith(prefix) for prefix in prefixes)}
    unexpected = sorted(remote_owned - expected_paths - {MANIFEST_PATH})

    def inspect(item: tuple[str, dict[str, Any]]) -> tuple[str, bool, bool, bool]:
        relative, entry = item
        status, body = public_get(http, relative)
        require(status in {200, 404}, f"public GET HTTP {status}: {relative}")
        listed = relative in listing
        found = status == 200
        checksum_unlisted_but_readable = (
            checksum_descriptor(relative) is not None and found and not listed
        )
        disagreement = listed != found and not checksum_unlisted_but_readable
        differs = found and (len(body) != entry["size"] or sha256_bytes(body) != entry["sha256"])
        return relative, found, differs, disagreement

    with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_PARALLEL_REQUESTS) as executor:
        inspected = list(executor.map(inspect, sorted(expected.items())))
    present = [relative for relative, found, _, _ in inspected if found]
    different = [relative for relative, _, differs, _ in inspected if differs]
    listing_disagreement = [relative for relative, _, _, disagreement in inspected if disagreement]

    manifest_status, manifest_body = public_get(http, MANIFEST_PATH)
    require(manifest_status in {200, 404}, f"public completion GET HTTP {manifest_status}")
    manifest_found = manifest_status == 200
    if (MANIFEST_PATH in listing) != manifest_found:
        listing_disagreement.append(MANIFEST_PATH)
    manifest_different = manifest_found and not completion_matches(manifest_body, manifest)

    conflict = bool(unexpected or different or listing_disagreement or manifest_different)
    if manifest_found and len(present) != len(expected):
        conflict = True
    if conflict:
        state = "CONFLICT"
    elif not manifest_found and not present:
        state = "ALL_ABSENT"
    elif not manifest_found:
        state = "PARTIAL_EXACT"
    else:
        require(len(present) == len(expected), "internal state classifier error")
        state = "ALL_COMPLETE_EXACT"

    for relative, entry in expected.items():
        read_bundle(bundle, relative, entry)

    return {
        "schema": "kuikly-publication-plan/v2",
        "state": state,
        "releaseSet": RELEASE,
        "sourceSha": manifest["source"]["commit"],
        "sourceTree": manifest["source"]["tree"],
        "setSha256": manifest["setSha256"],
        "candidateManifestSha256": sha256_bytes(json_bytes(manifest)),
        "productFileCount": len(expected),
        "presentCount": len(present),
        "ownedPrefixes": sorted(prefixes),
        "unexpected": unexpected,
        "different": different,
        "listingDisagreement": sorted(listing_disagreement),
        "completionPresent": manifest_found,
    }


def validate_plan(plan: dict[str, Any], manifest: dict[str, Any]) -> None:
    require(plan.get("schema") == "kuikly-publication-plan/v2", "unsupported publication plan")
    require(plan.get("releaseSet") == RELEASE, "publication plan release mismatch")
    require(plan.get("sourceSha") == manifest["source"]["commit"], "publication plan source mismatch")
    require(plan.get("sourceTree") == manifest["source"]["tree"], "publication plan tree mismatch")
    require(plan.get("setSha256") == manifest["setSha256"], "publication plan set mismatch")
    require(plan.get("candidateManifestSha256") == sha256_bytes(json_bytes(manifest)), "publication plan manifest bytes mismatch")
    require(plan.get("productFileCount") == len(manifest["publications"]), "publication plan count mismatch")
    expected_prefixes = set(owned_prefixes(manifest["publications"]))
    expected_prefixes.add(MANIFEST_PATH.rsplit("/", 1)[0] + "/")
    require(plan.get("ownedPrefixes") == sorted(expected_prefixes), "publication plan owned-prefix set mismatch")
    require(plan.get("state") in {"ALL_ABSENT", "PARTIAL_EXACT", "ALL_COMPLETE_EXACT"}, "publication plan is conflicting")


def exact_remote(http: Http, entry: dict[str, Any]) -> bool:
    status, body = public_get(http, entry["path"])
    if status == 404:
        return False
    require(status == 200, f"public GET HTTP {status}: {entry['path']}")
    require(
        len(body) == entry["size"] and sha256_bytes(body) == entry["sha256"],
        f"existing remote bytes conflict: {entry['path']}",
    )
    return True


def put_and_readback(http: Http, token: str, entry: dict[str, Any], body: bytes, *, content_type: str) -> str:
    if exact_remote(http, entry):
        return "reused"
    status, _ = http.request(
        PUBLIC_MAVEN_ORIGIN,
        repository_path(entry["path"]),
        "PUT",
        body=body,
        token=token,
        content_type=content_type,
    )
    require(status in {200, 201, 204, 409}, f"PUT failed with HTTP {status}: {entry['path']}")
    require(exact_remote(http, entry), f"uploaded bytes not publicly readable: {entry['path']}")
    return "uploaded" if status != 409 else "reused-after-race"


def verify_products(http: Http, bundle: Path, objects: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    def verify(entry: dict[str, Any]) -> dict[str, Any]:
        expected = read_bundle(bundle, entry["path"], entry)
        status, body = public_get(http, entry["path"])
        require(status == 200, f"public readback HTTP {status}: {entry['path']}")
        require(body == expected, f"public readback differs: {entry['path']}")
        return entry

    with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_PARALLEL_REQUESTS) as executor:
        return list(executor.map(verify, objects))


def release(
    http: Http,
    manifest: dict[str, Any],
    plan: dict[str, Any],
    bundle: Path,
    token: str,
    receipt_output: Path,
) -> None:
    validate_manifest(manifest, require_publishable=True)
    validate_plan(plan, manifest)
    require(token != "" and "\r" not in token and "\n" not in token, f"{PUBLISH_TOKEN_ENV} is missing or malformed")

    fresh = classify(http, manifest, bundle)
    require(fresh["state"] != "CONFLICT", "live publication state conflicts with the candidate")
    objects = [
        {"path": item["path"], "sha256": item["sha256"], "size": item["size"]}
        for item in sorted(manifest["publications"], key=lambda item: item["path"])
    ]
    final_manifest, final_body = completion_manifest(manifest)
    validate_manifest(final_manifest, require_publishable=True)
    final_entry = {"path": MANIFEST_PATH, "sha256": sha256_bytes(final_body), "size": len(final_body)}

    receipt: dict[str, Any] = {
        "schema": "kuikly-maven-publication/v1",
        "state": "publishing",
        "releaseSet": RELEASE,
        "sourceSha": manifest["source"]["commit"],
        "sourceTree": manifest["source"]["tree"],
        "setSha256": manifest["setSha256"],
        "uploaded": [],
        "reused": [],
    }

    def persist() -> None:
        receipt_output.parent.mkdir(parents=True, exist_ok=True)
        temporary = receipt_output.with_name(f".{receipt_output.name}.tmp-{os.getpid()}")
        try:
            with temporary.open("wb") as stream:
                stream.write(json_bytes(receipt))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, receipt_output)
            directory_fd = os.open(receipt_output.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if temporary.exists():
                temporary.unlink()

    persist()
    try:
        def publish(entry: dict[str, Any]) -> tuple[dict[str, Any], str]:
            body = read_bundle(bundle, entry["path"], entry)
            result = put_and_readback(http, token, entry, body, content_type="application/octet-stream")
            return entry, result

        with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_PARALLEL_REQUESTS) as executor:
            for entry, result in executor.map(publish, objects):
                receipt["reused" if result.startswith("reused") else "uploaded"].append(entry["path"])
                persist()

        readback = verify_products(http, bundle, objects)
        require(canonical_set_digest(readback) == manifest["setSha256"], "public product set digest mismatch")
        receipt["state"] = "products-public-readback-complete"
        receipt["publicReadbackCount"] = len(readback)
        persist()

        result = put_and_readback(
            http,
            token,
            final_entry,
            final_body,
            content_type="application/json; charset=utf-8",
        )
        receipt["reused" if result.startswith("reused") else "uploaded"].append(MANIFEST_PATH)
        status, remote = public_get(http, MANIFEST_PATH)
        require(status == 200 and remote == final_body, "completion manifest public readback is not byte-identical")
        receipt["state"] = "complete"
        receipt["completionManifest"] = final_entry
        receipt["uploadedCount"] = len(receipt["uploaded"])
        receipt["reusedCount"] = len(receipt["reused"])
        persist()
    except Exception as error:
        receipt["state"] = "incomplete-retryable"
        receipt["error"] = f"{type(error).__name__}: {error}"
        persist()
        raise


def command_plan(args: argparse.Namespace) -> None:
    manifest_path = Path(args.manifest).resolve()
    bundle = Path(args.bundle).resolve()
    output = Path(args.output).resolve()
    manifest = load_json(manifest_path, "candidate manifest")
    plan = classify(Http(), manifest, bundle)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(json_bytes(plan))
    print(f"publication plan: {plan['state']} present={plan['presentCount']}/{plan['productFileCount']}")


def command_release(args: argparse.Namespace) -> None:
    manifest = load_json(Path(args.manifest).resolve(), "candidate manifest")
    plan = load_json(Path(args.plan).resolve(), "publication plan")
    token = os.environ.get(PUBLISH_TOKEN_ENV, "")
    release(
        Http(),
        manifest,
        plan,
        Path(args.bundle).resolve(),
        token,
        Path(args.output).resolve(),
    )
    print(f"release complete: {RELEASE}")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)
    plan = commands.add_parser("plan", help="classify public state without credentials")
    plan.add_argument("--manifest", required=True)
    plan.add_argument("--bundle", required=True)
    plan.add_argument("--output", required=True)
    plan.set_defaults(handler=command_plan)
    publish = commands.add_parser("release", help="publish/retry with one repository token")
    publish.add_argument("--manifest", required=True)
    publish.add_argument("--plan", required=True)
    publish.add_argument("--bundle", required=True)
    publish.add_argument("--output", required=True)
    publish.set_defaults(handler=command_release)
    return root


def main(argv: Sequence[str] | None = None) -> int:
    try:
        args = parser().parse_args(argv)
        args.handler(args)
        return 0
    except (PublishError, OSError, ValueError, KeyError, TypeError) as error:
        print(f"kuikly publish error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
