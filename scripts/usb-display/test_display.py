"""Regression checks for incorrect counts, incomplete reads and unreadable pages."""

import subprocess
import tempfile
import unittest
from dataclasses import replace
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from board import BoardReadError, parse_pages, read_board
from display import Feed
from render import pages, render

NOW = datetime(2026, 11, 9, 16, tzinfo=UTC)


def item(key, state="active", *, family="feature-goal", finished=None, execution=None):
    return {
        "repository_root": "/work/SkillBill",
        "finished_at": finished,
        "snapshot": {
            "contract_version": "0.6",
            "execution_id": execution or key,
            "issue_key": key,
            "workflow_family": family,
            "lifecycle_state": state,
            "current_step": {"label": "implement"},
            "active_duration_ms": 60000,
            "started_at": "2026-11-09T12:00:00Z",
        },
    }


def page(*items, cursor=None):
    return {
        "contract_version": "1.3",
        "observed_at": NOW.isoformat(),
        "items": list(items),
        "diagnostics": [],
        "next_cursor": cursor,
    }


class DisplayTest(unittest.TestCase):
    def test_blocked_runs_precede_running_and_paused_across_display_pages(self):
        for family in ("feature-goal", "feature-task-runtime", "feature-verify"):
            with self.subTest(family=family):
                states = (
                    "paused", "active", "failed", "blocked",
                    "idle", "active", "blocked", "paused",
                )
                items = [
                    item(str(index), state, family=family)
                    for index, state in enumerate(states)
                ]
                board = parse_pages([page(*items[:4]), page(*items[4:])])
                expected = ["3", "6", "1", "5", "2"]
                if family == "feature-goal":
                    expected.append("4")
                expected.extend(["0", "7"])
                self.assertEqual(
                    expected,
                    [row.key for _, rows in pages(board) for row in rows],
                )

    def test_goals_keep_start_time_order_within_each_status(self):
        newer = item("newer")
        newer["snapshot"]["started_at"] = "2026-11-09T13:00:00Z"
        board = parse_pages([
            page(newer, item("paused", "paused"), item("older"), item("blocked", "blocked"))
        ])
        self.assertEqual(
            ["blocked", "older", "newer", "paused"],
            [row.key for row in board.rows],
        )

    def test_paused_and_blocked_runs_count_as_needs_you_rather_than_running(self):
        board = parse_pages(
            [
                page(
                    item("A"),
                    item("B", "paused"),
                    item("C", "blocked"),
                    item("D", "idle"),
                    item("E", "active", family="feature-verify"),
                )
            ]
        )
        self.assertEqual((2, 3, 0), (board.running, board.needs_you, board.finished))
        self.assertEqual(
            "Operations", next(r for r in board.rows if r.key == "E").group
        )
        self.assertEqual("Needs you", next(r for r in board.rows if r.key == "D").phase)

    def test_history_of_same_issue_does_not_hide_live_run_or_count_as_running(self):
        board = parse_pages(
            [
                page(
                    item("A"),
                    item(
                        "A",
                        "canceled",
                        finished="2026-11-09T13:00:00Z",
                        execution="old",
                    ),
                    item("B", "terminal", finished="2026-11-09T14:00:00Z"),
                    item("C", "terminal", finished="2026-11-08T10:00:00Z"),
                )
            ]
        )
        self.assertEqual((1, 0, 2), (board.running, board.needs_you, board.finished))
        self.assertEqual(["A"], [r.key for r in board.rows])

    def test_all_status_pages_contribute_to_counts(self):
        import json

        responses = [
            subprocess.CompletedProcess(
                [], 0, json.dumps(page(item("A"), cursor="next")), ""
            ),
            subprocess.CompletedProcess(
                [], 0, json.dumps(page(item("B", "paused"))), ""
            ),
        ]
        with patch("board.subprocess.run", side_effect=responses) as command:
            board = read_board("/bin/sona")
        self.assertEqual((1, 1), (board.running, board.needs_you))
        self.assertEqual(["--cursor", "next"], command.call_args.args[0][-2:])

    def test_broken_later_page_keeps_previous_snapshot_and_marks_it_stale(self):
        import json

        old = parse_pages([page(item("A"))])
        feed = Feed("/bin/sona", None, 10)
        feed.board = old
        responses = [
            subprocess.CompletedProcess(
                [], 0, json.dumps(page(item("B"), cursor="next")), ""
            ),
            subprocess.CompletedProcess([], 1, "", "Database unavailable"),
        ]
        with (
            patch("board.subprocess.run", side_effect=responses),
            self.assertLogs("skill-bill-usb-display", "ERROR"),
        ):
            feed.refresh()
        board, error = feed.snapshot()
        self.assertIs(board, old)
        self.assertIn("Database unavailable", error)
        with (
            patch("display.read_board", return_value=old),
            self.assertLogs("skill-bill-usb-display", "INFO"),
        ):
            feed.refresh()
        self.assertIsNone(feed.snapshot()[1])

    def test_unknown_contract_or_duplicate_page_cannot_show_plausible_counts(self):
        unsupported = page(item("A"))
        unsupported["contract_version"] = "99"
        with self.assertRaises(BoardReadError):
            parse_pages([unsupported])
        with self.assertRaises(BoardReadError):
            parse_pages([page(item("A")), page(item("A"))])

    def test_overflow_and_other_run_kinds_remain_visible_on_later_pages(self):
        board = parse_pages(
            [
                page(
                    *(item(str(i)) for i in range(5)),
                    item("verify", family="feature-verify"),
                    item("validation", family="feature-task-runtime"),
                )
            ]
        )
        all_pages = pages(board)
        self.assertEqual(4, len(all_pages))
        self.assertEqual(
            {r.identity for r in board.rows},
            {r.identity for _, rows in all_pages for r in rows},
        )
        for index in range(len(all_pages)):
            image = render(board, NOW, index)
            self.assertEqual((320, 480), image.size)
            self.assertEqual("RGB", image.mode)
        render(None, NOW, error="Unavailable")
        render(board, NOW, error="Unavailable")

    def test_failed_usb_handoff_restores_the_original_monitor(self):
        import install

        def controller(*args, check=True):
            enabled = args[-1] == install.MONITOR
            return subprocess.CompletedProcess(
                args, 0 if enabled or args[0] not in {"is-active", "is-enabled"} else 1
            )

        def command(argv, **kwargs):
            if "--once" in argv:
                raise subprocess.CalledProcessError(1, argv)
            return subprocess.CompletedProcess(argv, 0)

        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            unit = home / ".config/systemd/user" / install.SERVICE
            unit.parent.mkdir(parents=True)
            unit.write_text("previous configuration")
            with (
                patch("install.Path.home", return_value=home),
                patch("install.shutil.which", return_value="/bin/sona"),
                patch("install.systemctl", side_effect=controller) as control,
                patch("install.subprocess.run", side_effect=command),
                self.assertRaises(subprocess.CalledProcessError),
            ):
                install.install(
                    SimpleNamespace(
                        replace_system_monitor=True,
                        rotate_180=True,
                        device="/dev/example",
                    )
                )
            self.assertEqual("previous configuration", unit.read_text())
            self.assertIn(
                unittest.mock.call("start", install.MONITOR, check=False),
                control.call_args_list,
            )
            self.assertIn(
                unittest.mock.call("enable", install.MONITOR, check=False),
                control.call_args_list,
            )

    def test_paused_duration_does_not_keep_ticking(self):
        board = parse_pages([page(item("A", "paused"))])
        row = replace(board.rows[0], duration_as_of=NOW.replace(hour=12))
        self.assertEqual("1m", row.duration(NOW))
        self.assertEqual("4h 01m", replace(row, state="active").duration(NOW))


if __name__ == "__main__":
    unittest.main()
