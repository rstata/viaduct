import os
import sys
import tempfile
import unittest
from pathlib import Path
from subprocess import run

sys.path.insert(0, str(Path(__file__).parent.parent))

from maybe_capture_build_scan_artifact import maybe_capture_build_scan_artifact


class TestMaybeCaptureBuildScanArtifact(unittest.TestCase):
    def test_skips_successful_commands(self):
        with tempfile.TemporaryDirectory() as tempdir:
            tempdir_path = Path(tempdir)
            log_file = tempdir_path / "gradle.log"
            log_file.write_text("https://scans.gradle.com/s/success123\n")

            captured = maybe_capture_build_scan_artifact(
                log_file=log_file,
                artifact_name="build.txt",
                status=0,
                artifact_dir=tempdir_path / "artifacts",
                pr_number="42",
            )

            self.assertIsNone(captured)
            self.assertFalse((tempdir_path / "artifacts").exists())

    def test_skips_failures_without_scan_urls(self):
        with tempfile.TemporaryDirectory() as tempdir:
            tempdir_path = Path(tempdir)
            log_file = tempdir_path / "gradle.log"
            log_file.write_text("plain gradle output\n")

            captured = maybe_capture_build_scan_artifact(
                log_file=log_file,
                artifact_name="build.txt",
                status=1,
                artifact_dir=tempdir_path / "artifacts",
                pr_number="42",
            )

            self.assertIsNone(captured)
            self.assertFalse((tempdir_path / "artifacts").exists())

    def test_writes_scan_url_and_pr_number_for_failures(self):
        with tempfile.TemporaryDirectory() as tempdir:
            tempdir_path = Path(tempdir)
            log_file = tempdir_path / "gradle.log"
            log_file.write_text("https://scans.gradle.com/s/failure123\n")

            captured = maybe_capture_build_scan_artifact(
                log_file=log_file,
                artifact_name="test.txt",
                status=1,
                artifact_dir=tempdir_path / "artifacts",
                pr_number="42",
            )

            self.assertEqual(captured, "https://scans.gradle.com/s/failure123")
            self.assertEqual(
                (tempdir_path / "artifacts" / "test.txt").read_text(),
                "https://scans.gradle.com/s/failure123\n",
            )
            self.assertEqual(
                (tempdir_path / "artifacts" / "pr-number.txt").read_text(),
                "42\n",
            )

    def test_cli_uses_pr_number_environment_variable(self):
        with tempfile.TemporaryDirectory() as tempdir:
            tempdir_path = Path(tempdir)
            log_file = tempdir_path / "gradle.log"
            log_file.write_text("https://gradle.com/s/cli123\n")

            result = run(
                [
                    sys.executable,
                    str(Path(__file__).parent.parent / "maybe_capture_build_scan_artifact.py"),
                    str(log_file),
                    "build.txt",
                    "--status",
                    "1",
                    "--artifact-dir",
                    str(tempdir_path / "artifacts"),
                ],
                capture_output=True,
                text=True,
                check=True,
                env={**os.environ, "PR_NUMBER": "99"},
            )

            self.assertIn("Captured build scan: https://gradle.com/s/cli123", result.stdout)
            self.assertEqual(
                (tempdir_path / "artifacts" / "build.txt").read_text(),
                "https://gradle.com/s/cli123\n",
            )
            self.assertEqual(
                (tempdir_path / "artifacts" / "pr-number.txt").read_text(),
                "99\n",
            )
