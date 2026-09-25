#!/usr/bin/env python3
"""Focused regression tests for the staging3 migration DCO contract."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("check-staging3-migration.py")
SPEC = importlib.util.spec_from_file_location("check_staging3_migration", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"unable to load {MODULE_PATH}")
MIGRATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MIGRATION)


class DcoContractTest(unittest.TestCase):
    def test_accepts_valid_signoff_independent_of_commit_identities(self) -> None:
        with patch.object(
            MIGRATION,
            "git",
            return_value="Ark <raft-mobile-ark@mail.build>",
        ):
            MIGRATION.require_dco("github-squash")

    def test_accepts_one_valid_signoff_among_multiple_trailers(self) -> None:
        with patch.object(
            MIGRATION,
            "git",
            return_value="malformed\nJony <jony@mail.build>",
        ):
            MIGRATION.require_dco("coauthored-squash")

    def test_rejects_missing_signoff(self) -> None:
        with patch.object(MIGRATION, "git", return_value=""):
            with self.assertRaisesRegex(AssertionError, "missing valid Signed-off-by"):
                MIGRATION.require_dco("unsigned")

    def test_rejects_malformed_signoff(self) -> None:
        with patch.object(MIGRATION, "git", return_value="Jony jony@mail.build"):
            with self.assertRaisesRegex(AssertionError, "missing valid Signed-off-by"):
                MIGRATION.require_dco("malformed")


if __name__ == "__main__":
    unittest.main()
