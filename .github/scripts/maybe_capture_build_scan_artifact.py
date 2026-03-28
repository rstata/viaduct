#!/usr/bin/env python3
"""Persist a failing Gradle build scan URL to workflow artifacts."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from extract_build_scan_url import extract_build_scan_url


def maybe_capture_build_scan_artifact(
    *,
    log_file: Path,
    artifact_name: str,
    status: int,
    artifact_dir: Path = Path("build-scan-urls"),
    pr_number: str = "",
) -> str | None:
    """Stores the last build scan URL when the Gradle command fails."""
    if status == 0:
        return None

    scan_url = extract_build_scan_url(log_file.read_text())
    if not scan_url:
        return None

    artifact_dir.mkdir(parents=True, exist_ok=True)
    (artifact_dir / artifact_name).write_text(f"{scan_url}\n")
    if pr_number:
        (artifact_dir / "pr-number.txt").write_text(f"{pr_number}\n")

    return scan_url


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("log_file")
    parser.add_argument("artifact_name")
    parser.add_argument("--status", required=True, type=int)
    parser.add_argument("--artifact-dir", default="build-scan-urls")
    parser.add_argument("--pr-number", default=os.environ.get("PR_NUMBER", ""))
    return parser.parse_args(argv[1:])


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    scan_url = maybe_capture_build_scan_artifact(
        log_file=Path(args.log_file),
        artifact_name=args.artifact_name,
        status=args.status,
        artifact_dir=Path(args.artifact_dir),
        pr_number=str(args.pr_number),
    )
    if scan_url:
        print(f"Captured build scan: {scan_url}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
