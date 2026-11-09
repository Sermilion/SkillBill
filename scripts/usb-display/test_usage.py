"""Catch wrong-provider quotas and expired limits rendered as fresh allowances."""

import tempfile
import unittest
from datetime import UTC, datetime, timedelta
from pathlib import Path
from unittest.mock import patch

from render import render
from usage import (
    Usage,
    UsageFeed,
    UsageReadError,
    Window,
    claude_reading,
    codex_windows,
    preferences,
)

NOW = datetime(2026, 11, 9, 17, tzinfo=UTC)
SAVED = (
    "v1;6;claude1;115;/profile/claude7;Allowed1;113;17941751293131;2"
    "16;claude:five_hour6;5 hour1;g0;13;179417512931310;17941920005;180004;10.0"
    "16;claude:seven_day6;Weekly1;g0;13;179417512931310;17944164006;6048004;86.0"
)


class UsageTest(unittest.TestCase):
    def test_saved_app_reading_keeps_distinct_short_and_weekly_limits(self):
        provider, profile, _, windows = claude_reading(SAVED)
        self.assertEqual(("claude", "/profile/claude"), (provider, profile))
        self.assertEqual(
            [("5 hour", 10), ("Weekly", 86)], [(w.label, w.used) for w in windows]
        )
        with self.assertRaises(ValueError):
            claude_reading(SAVED + "trailing")

    def test_preferences_reader_follows_the_apps_current_provider(self):
        # DataStore Preferences map entry with key usage_last_agent and string value codex.
        fixture = b"\x0a\x1b\x0a\x10usage_last_agent\x12\x07\x2a\x05codex"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "settings.preferences_pb"
            path.write_bytes(fixture)
            self.assertEqual("codex", preferences(path)["usage_last_agent"])
            path.write_bytes(fixture[:-1])
            with self.assertRaises(UsageReadError):
                preferences(path)

    def test_context_switch_during_codex_read_discards_its_response(self):
        feed = UsageFeed("settings", "codex", None)
        settings = [{"usage_last_agent": "codex"}, {"usage_last_agent": "claude"}]
        window = Window("Weekly", 86, NOW + timedelta(days=1), NOW, 604800)
        with (
            patch("usage.preferences", side_effect=settings),
            patch("usage.read_codex", return_value=(window,)),
            self.assertLogs("skill-bill-usb-display", "INFO"),
        ):
            feed.refresh(NOW, 0)
        self.assertEqual("claude", feed.snapshot().provider)
        self.assertEqual((), feed.snapshot().windows)

    def test_claude_selection_does_not_reuse_another_profiles_numbers(self):
        feed = UsageFeed("settings", "codex", None)
        settings = {
            "usage_last_agent": "claude",
            "claude_config_dir": "/different/profile",
            "usage_last_readings": [SAVED],
        }
        with patch("usage.preferences", return_value=settings):
            feed.refresh(NOW, 0)
        self.assertEqual((), feed.snapshot().windows)
        self.assertEqual("Shows after the next Claude reply", feed.snapshot().message)

    def test_failed_codex_refresh_keeps_last_numbers_with_a_saved_warning(self):
        feed = UsageFeed("settings", "codex", None)
        window = Window("Weekly", 86, NOW + timedelta(days=1), NOW, 604800)
        feed.publish(Usage("codex", (window,), ""))
        with (
            patch("usage.preferences", return_value={"usage_last_agent": "codex"}),
            patch("usage.read_codex", side_effect=UsageReadError("Unavailable")),
            self.assertLogs("skill-bill-usb-display", "WARNING"),
        ):
            feed.refresh(NOW, 0)
        self.assertEqual((window,), feed.snapshot().windows)
        self.assertEqual("Usage unavailable", feed.snapshot().message)

    def test_expired_window_never_claims_a_reset_or_zero_usage(self):
        window = Window(
            "5 hour", 90, NOW - timedelta(minutes=1), NOW - timedelta(hours=2), 18000
        )
        with patch("render.text") as text:
            render(None, NOW, usage=Usage("claude", (window,), ""))
        painted = [call.args[2] for call in text.call_args_list]
        self.assertIn("Awaiting update", painted)
        self.assertNotIn("0% used", painted)
        self.assertNotIn("90% used", painted)

    def test_codex_uses_multi_bucket_response_and_rejects_invalid_percentages(self):
        result = {
            "rateLimitsByLimitId": {
                "codex": {
                    "primary": {
                        "usedPercent": 35,
                        "windowDurationMins": 300,
                        "resetsAt": 1794300000,
                    },
                    "secondary": None,
                }
            },
            "rateLimits": {"limitId": "ignored"},
        }
        windows = codex_windows(result, NOW)
        self.assertEqual([("5 hour", 35)], [(w.label, w.used) for w in windows])
        result["rateLimitsByLimitId"]["codex"]["primary"]["usedPercent"] = 101
        with self.assertRaises(UsageReadError):
            codex_windows(result, NOW)


if __name__ == "__main__":
    unittest.main()
