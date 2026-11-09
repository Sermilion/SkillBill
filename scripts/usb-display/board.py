"""Read the existing work-board wire format without opening runtime storage."""

import json
import subprocess
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path


class BoardReadError(RuntimeError):
    pass


def instant(value):
    return datetime.fromisoformat(value)


@dataclass(frozen=True)
class Row:
    identity: str
    key: str
    repository: str
    group: str
    state: str
    phase: str
    duration_ms: int
    duration_as_of: datetime | None
    progress: tuple[int, int] | None
    started_at: str

    def duration(self, now):
        extra = (
            max(0, (now - self.duration_as_of).total_seconds() * 1000)
            if self.duration_as_of and self.state == "active"
            else 0
        )
        minutes = int((self.duration_ms + extra) // 60000)
        return (
            f"{minutes // 60}h {minutes % 60:02}m" if minutes >= 60 else f"{minutes}m"
        )


@dataclass(frozen=True)
class Board:
    rows: tuple[Row, ...]
    running: int
    needs_you: int
    finished: int
    observed_at: datetime
    warnings: tuple[str, ...]


def parse_pages(pages):
    """Match the tray's section counts, including paused goals under Needs you."""
    rows = []
    running = needs_you = finished = 0
    seen = set()
    warnings = []
    observed = instant(pages[0]["observed_at"])
    for page in pages:
        if page.get("contract_version") != "1.3":
            raise BoardReadError(
                "Unsupported work-board contract. Update the display integration."
            )
        if page.get("problem"):
            raise BoardReadError(
                str(page["problem"].get("message", "Work board unavailable"))
            )
        warnings.extend(d["reason"] for d in page["diagnostics"])
        for item in page["items"]:
            s = item["snapshot"]
            if s["contract_version"] != "0.6":
                raise BoardReadError(
                    "Unsupported status contract. Update the display integration."
                )
            identity = (
                s.get("execution_id") or s.get("invocation_id") or s.get("workflow_id")
            )
            if not identity:
                raise BoardReadError("Work item has no identity")
            if identity in seen:
                raise BoardReadError(
                    "Board changed while paging. Retrying on the next refresh."
                )
            seen.add(identity)
            state = s["lifecycle_state"]
            if state not in {
                "active",
                "paused",
                "blocked",
                "failed",
                "idle",
                "terminal",
                "canceled",
            }:
                raise BoardReadError(f"Unknown lifecycle state: {state}")
            end = item["finished_at"]
            if end:
                if observed - timedelta(hours=24) <= instant(end) <= observed:
                    finished += 1
                continue
            family = s.get("workflow_family")
            goal = family == "feature-goal" or s.get("launch_scope") == "goal"
            group = (
                "Goals"
                if goal
                else "Operations"
                if family == "feature-verify"
                else "Phases"
            )
            live = (
                state in {"active", "paused", "blocked", "failed"}
                or state == "idle"
                and goal
            )
            if not live:
                continue
            running += state == "active"
            needs_you += state != "active"
            phase = s["current_step"]["label"]
            execution = s.get("current_phase_execution")
            if execution:
                kind = execution["kind"]
                label = {
                    "pass": "pass",
                    "semantic_loop": "loop",
                    "gate_run": "gate run",
                    "bounded_edge": "edge",
                    "attempt": "run",
                }.get(kind)
                if label is None:
                    raise BoardReadError(f"Unknown phase execution kind: {kind}")
                phase = f"{execution['phase_id']} · {label} {execution['count']}"
            if state != "active":
                phase = "Needs you" if state == "idle" else state.capitalize()
            progress = s.get("progress")
            rows.append(
                Row(
                    identity,
                    s.get("issue_key") or s.get("phase_id") or group.rstrip("s"),
                    Path(item["repository_root"]).name,
                    group,
                    state,
                    phase,
                    s.get("active_duration_ms") or 0,
                    instant(s["active_duration_as_of"])
                    if s.get("active_duration_as_of")
                    else None,
                    (progress["completed"], progress["total"]) if progress else None,
                    s.get("started_at") or "",
                )
            )
    # The tray orders goal rows by start time, and keeps runtime order for the other tabs.
    rows = sorted(
        (r for r in rows if r.group == "Goals"), key=lambda r: r.started_at
    ) + [r for r in rows if r.group != "Goals"]
    return Board(tuple(rows), running, needs_you, finished, observed, tuple(warnings))


def read_board(cli, timeout=20):
    deadline = time.monotonic() + timeout
    pages = []
    cursors = set()
    cursor = None
    while True:
        command = [
            cli,
            "work",
            "board",
            "--finished-within",
            "24h",
            "--page-size",
            "100",
        ]
        if cursor:
            command.extend(["--cursor", cursor])
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise BoardReadError("Status refresh timed out while paging")
        try:
            result = subprocess.run(
                command, capture_output=True, text=True, timeout=remaining, check=False
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise BoardReadError(f"Status command unavailable: {exc}") from exc
        if result.returncode:
            raise BoardReadError(
                f"Status command exited {result.returncode}: {result.stderr.strip()[-300:]}"
            )
        try:
            page = json.loads(result.stdout)
            pages.append(page)
            cursor = page["next_cursor"]
            if cursor is not None and (
                not isinstance(cursor, str) or not cursor or cursor in cursors
            ):
                raise BoardReadError("Invalid or repeated board cursor")
            if not cursor:
                return parse_pages(pages)
            cursors.add(cursor)
            if len(pages) >= 100:
                raise BoardReadError("Work board exceeds 100 pages. Counts withheld.")
        except (ValueError, KeyError, TypeError, AttributeError, IndexError) as exc:
            raise BoardReadError(f"Invalid status response: {exc}") from exc
