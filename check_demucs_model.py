#!/usr/bin/env python3
"""Check availability of the HTDemucs model used by Smart Voice replacement.

The script performs:
1. HEAD request to inspect redirects and headers.
2. Small ranged GET request to verify that model bytes can actually be read.
3. Optional full download check via --full.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

MODEL_NAME = "955717e8-8726e21a.th"
MODEL_URL = (
    "https://huggingface.co/Politrees/UVR_resources/resolve/main/"
    f"models/Demucs/Demucs_v4/{MODEL_NAME}?download=true"
)
DEFAULT_SAMPLE_BYTES = 1024 * 1024


def mib(value: int | None) -> str:
    if value is None or value < 0:
        return "unknown"
    return f"{value / (1024 * 1024):.2f} MiB"


def request(url: str, method: str = "GET", range_bytes: int | None = None, timeout: int = 60):
    headers = {"User-Agent": "FFmpegMediaWorkshop-model-check/1.0"}
    if range_bytes is not None:
        headers["Range"] = f"bytes=0-{range_bytes - 1}"
    req = urllib.request.Request(url, headers=headers, method=method)
    return urllib.request.urlopen(req, timeout=timeout)


def print_headers(response) -> None:
    print(f"status: {response.status} {response.reason}")
    print(f"final_url: {response.geturl()}")
    for name in [
        "content-type",
        "content-length",
        "content-range",
        "accept-ranges",
        "etag",
        "x-linked-size",
        "x-repo-commit",
    ]:
        value = response.headers.get(name)
        if value:
            print(f"{name}: {value}")


def check_head(url: str, timeout: int) -> None:
    print("== HEAD ==")
    try:
        with request(url, method="HEAD", timeout=timeout) as response:
            print_headers(response)
    except urllib.error.HTTPError as error:
        print(f"HEAD failed: HTTP {error.code} {error.reason}")
        print("Continuing with ranged GET...")
    except Exception as error:
        print(f"HEAD failed: {error}")
        print("Continuing with ranged GET...")


def check_range(url: str, sample_bytes: int, timeout: int) -> bytes:
    print("\n== RANGED GET ==")
    started = time.time()
    with request(url, range_bytes=sample_bytes, timeout=timeout) as response:
        print_headers(response)
        data = response.read(sample_bytes)
    elapsed = max(time.time() - started, 0.001)
    print(f"read: {len(data)} bytes ({mib(len(data))})")
    print(f"speed: {mib(int(len(data) / elapsed))}/s")
    print(f"sha256(sample): {hashlib.sha256(data).hexdigest()}")
    if len(data) == 0:
        raise RuntimeError("No bytes were read from the model URL")
    return data


def check_full(url: str, destination: Path, timeout: int) -> None:
    print("\n== FULL DOWNLOAD ==")
    destination.parent.mkdir(parents=True, exist_ok=True)
    tmp = destination.with_suffix(destination.suffix + ".part")
    digest = hashlib.sha256()
    downloaded = 0
    started = time.time()
    with request(url, timeout=timeout) as response:
        print_headers(response)
        total = int(response.headers.get("content-length") or -1)
        with tmp.open("wb") as output:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)
                digest.update(chunk)
                downloaded += len(chunk)
                if total > 0:
                    percent = downloaded / total * 100
                    print(f"\rdownloaded: {percent:5.1f}% ({mib(downloaded)} / {mib(total)})", end="")
                else:
                    print(f"\rdownloaded: {mib(downloaded)}", end="")
    print()
    elapsed = max(time.time() - started, 0.001)
    print(f"saved_part: {tmp}")
    print(f"size: {downloaded} bytes ({mib(downloaded)})")
    print(f"speed: {mib(int(downloaded / elapsed))}/s")
    print(f"sha256(full): {digest.hexdigest()}")
    tmp.replace(destination)
    print(f"saved: {destination}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=MODEL_URL)
    parser.add_argument("--sample-bytes", type=int, default=DEFAULT_SAMPLE_BYTES)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--full", action="store_true", help="download the complete model")
    parser.add_argument(
        "--output",
        default=str(Path.home() / ".cache" / "torch" / "hub" / "checkpoints" / MODEL_NAME),
        help="destination for --full",
    )
    args = parser.parse_args()

    print(f"model: {MODEL_NAME}")
    print(f"url: {args.url}")
    try:
        check_head(args.url, args.timeout)
        check_range(args.url, args.sample_bytes, args.timeout)
        if args.full:
            check_full(args.url, Path(args.output), args.timeout)
    except Exception as error:
        print(f"\nFAILED: {error}", file=sys.stderr)
        return 1

    print("\nOK: model URL is reachable and returned bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
