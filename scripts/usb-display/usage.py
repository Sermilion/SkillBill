"""Read the app's provider selection, saved Claude windows and Codex account limits."""

import json
import logging
import math
import os
import selectors
import subprocess
import threading
import time
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from pathlib import Path

LOG = logging.getLogger("skill-bill-usb-display")


class UsageReadError(ValueError):
    pass


@dataclass(frozen=True)
class Window:
    label: str
    used: float
    reset_at: datetime | None
    observed_at: datetime
    seconds: int

    def __post_init__(self):
        if (
            not self.label
            or self.seconds <= 0
            or not math.isfinite(self.used)
            or not 0 <= self.used <= 100
        ):
            raise UsageReadError("Invalid usage percentage")


@dataclass(frozen=True)
class Usage:
    provider: str | None = None
    windows: tuple[Window, ...] = ()
    message: str = "Follow the app to select a provider"


def protobuf_fields(data):
    """Decode bounded scalar fields of Android DataStore Preferences, read-only."""
    offset = 0

    def varint():
        nonlocal offset
        value = 0
        for shift in range(0, 70, 7):
            if offset >= len(data):
                raise UsageReadError("Truncated preferences")
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value
        raise UsageReadError("Invalid preferences varint")

    while offset < len(data):
        tag = varint()
        number, kind = tag >> 3, tag & 7
        if not number:
            raise UsageReadError("Invalid preferences field")
        if kind == 0:
            yield number, varint()
            continue
        if kind == 2:
            length = varint()
        elif kind in (1, 5):
            length = 8 if kind == 1 else 4
        else:
            raise UsageReadError("Unsupported preferences wire type")
        end = offset + length
        if end > len(data):
            raise UsageReadError("Truncated preferences field")
        yield number, data[offset:end]
        offset = end


def preferences(path):
    with Path(path).open("rb") as source:
        data = source.read(1024 * 1024 + 1)
    if len(data) > 1024 * 1024:
        raise UsageReadError("Preferences exceed the display reader's size limit")
    result = {}
    for number, entry in protobuf_fields(data):
        if number != 1:
            continue
        fields = dict(protobuf_fields(entry))
        key = fields[1].decode("utf-8")
        if key not in {
            "usage_last_agent",
            "default_agent",
            "claude_config_dir",
            "usage_last_readings",
        }:
            continue
        value = dict(protobuf_fields(fields[2]))
        if key == "usage_last_readings":
            result[key] = [
                v.decode("utf-8") for n, v in protobuf_fields(value[6]) if n == 1
            ]
        else:
            result[key] = value[5].decode("utf-8")
    return result


def claude_reading(value):
    if not value.startswith("v1;"):
        raise UsageReadError("Unsupported saved usage encoding")
    # Kotlin String.length counts UTF-16 units, including pairs in names outside the BMP.
    raw = value.encode("utf-16-le")
    units = "".join(
        chr(int.from_bytes(raw[i : i + 2], "little")) for i in range(0, len(raw), 2)
    )
    offset = 3

    def part():
        nonlocal offset
        separator = units.index(";", offset)
        length = int(units[offset:separator])
        start, end = separator + 1, separator + 1 + length
        if length < 0 or end > len(units):
            raise UsageReadError("Invalid saved usage length")
        offset = end
        return units[start:end].encode("utf-16-le", "surrogatepass").decode("utf-16-le")

    provider, has_profile, profile = part(), part(), part()
    permission, warning, observed, count = part(), part(), part(), int(part())
    if (
        provider not in {"codex", "claude"}
        or has_profile not in {"0", "1"}
        or permission not in {"Allowed", "Rejected", "Unknown"}
        or warning not in {"0", "1"}
        or not 0 <= count <= 64
    ):
        raise UsageReadError("Invalid saved usage header")
    meters = []
    for _ in range(count):
        bucket, label, scope, name, at, reset, seconds, used = [
            part() for _ in range(8)
        ]
        if not bucket or not label or scope not in {"g", "m", "p"}:
            raise UsageReadError("Invalid saved usage window")
        meters.append(
            Window(
                label if scope == "g" else f"{label} ({name})",
                float(used),
                datetime.fromtimestamp(int(reset), UTC) if reset else None,
                datetime.fromtimestamp(int(at) / 1000, UTC),
                int(seconds) if seconds else 2**63 - 1,
            )
        )
    if offset != len(units):
        raise UsageReadError("Unexpected saved usage suffix")
    return (
        provider,
        profile if has_profile == "1" else None,
        datetime.fromtimestamp(int(observed) / 1000, UTC),
        tuple(sorted(meters, key=lambda m: m.seconds)),
    )


def codex_windows(result, now):
    buckets = result.get("rateLimitsByLimitId")
    if buckets is None:
        legacy = result["rateLimits"]
        buckets = (
            {legacy["limitId"]: legacy}
            if "primary" in legacy or "secondary" in legacy
            else legacy
        )
    if isinstance(buckets, list):
        buckets = {bucket["limitId"]: bucket for bucket in buckets}
    windows = []
    for limit_id, bucket in buckets.items():
        for key in ("primary", "secondary"):
            window = bucket.get(key)
            if window is None:
                continue
            minutes = int(window["windowDurationMins"])
            if minutes <= 0:
                raise UsageReadError("Invalid Codex usage window")
            label = (
                "Weekly"
                if minutes == 10080
                else f"{minutes // 60} hour"
                if minutes % 60 == 0
                else f"{minutes} min"
            )
            if limit_id != "codex":
                label += f" ({bucket.get('limitName') or limit_id})"
            reset = window.get("resetsAt")
            windows.append(
                Window(
                    label,
                    float(window["usedPercent"]),
                    datetime.fromtimestamp(reset, UTC) if reset is not None else None,
                    now,
                    minutes * 60,
                )
            )
    return tuple(sorted(windows, key=lambda m: (m.seconds, m.label)))


def read_codex(executable, now, timeout=15):
    if not executable:
        raise UsageReadError("Codex executable unavailable")
    with subprocess.Popen(
        [executable, "app-server", "--listen", "stdio://"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        bufsize=0,
    ) as process:
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                deadline = time.monotonic() + timeout
                buffered = bytearray()
                sequence = 0
                total = 0

                def rpc(method, params):
                    nonlocal sequence, total
                    sequence += 1
                    process.stdin.write(
                        (
                            json.dumps(
                                {"id": sequence, "method": method, "params": params}
                            )
                            + "\n"
                        ).encode()
                    )
                    while True:
                        while b"\n" in buffered:
                            line, _, rest = buffered.partition(b"\n")
                            buffered[:] = rest
                            message = json.loads(line)
                            if message.get("id") == sequence:
                                if "error" in message:
                                    raise UsageReadError(f"Codex rejected {method}")
                                return message["result"]
                            if "method" in message and "id" in message:
                                raise UsageReadError(
                                    "Codex requested an unsupported authentication interaction"
                                )
                        remaining = deadline - time.monotonic()
                        if remaining <= 0 or not selector.select(remaining):
                            raise UsageReadError("Codex usage read timed out")
                        chunk = os.read(process.stdout.fileno(), 65536)
                        total += len(chunk)
                        if not chunk or total > 2 * 1024 * 1024:
                            raise UsageReadError(
                                "Codex usage response ended or exceeded its size limit"
                            )
                        buffered.extend(chunk)

                rpc(
                    "initialize",
                    {
                        "clientInfo": {
                            "name": "skill-bill-usb-display",
                            "version": "1",
                        },
                        "capabilities": {"experimentalApi": True},
                    },
                )
                process.stdin.write(b'{"method":"initialized","params":{}}\n')
                account = rpc("account/read", {"refreshToken": False}).get("account")
                if not account or account.get("type") != "chatgpt":
                    raise UsageReadError("Codex ChatGPT sign-in required")
                return codex_windows(rpc("account/rateLimits/read", {}), now)
        finally:
            process.kill()
            process.wait()


class UsageFeed:
    def __init__(self, path, codex, stop):
        self.path, self.codex, self.stop = path, codex, stop
        self.lock = threading.Lock()
        self.value = Usage()
        self.last_codex = float("-inf")
        self.codex_value = Usage("codex", message="Loading usage…")
        self.last_error = None

    def snapshot(self):
        with self.lock:
            return self.value

    def publish(self, value):
        with self.lock:
            self.value = value

    def refresh(self, now, monotonic):
        selected = None
        try:
            settings = preferences(self.path)
            selected = settings.get("usage_last_agent") or settings.get("default_agent")
            if selected not in {"codex", "claude"}:
                selected = None
                raise UsageReadError("No Codex or Claude context selected")
            current = self.snapshot()
            if current.provider != selected:
                current = (
                    self.codex_value
                    if selected == "codex"
                    else Usage(selected, message="Loading usage…")
                )
                self.publish(current)
            if selected == "claude":
                profile = settings.get("claude_config_dir") or str(
                    Path.home() / ".claude"
                )
                readings = [
                    claude_reading(v) for v in settings.get("usage_last_readings", [])
                ]
                readings = [r for r in readings if r[0] == selected and r[1] == profile]
                windows = max(readings, key=lambda r: r[2])[3] if readings else ()
                self.publish(
                    Usage(
                        selected,
                        windows,
                        "Shows after the next Claude reply" if not windows else "",
                    )
                )
            elif current.windows and monotonic - self.last_codex < 60:
                return
            else:
                # Rate-limit retries share the same minimum interval, including after failures.
                if monotonic - self.last_codex < 60:
                    return
                self.last_codex = monotonic
                windows = read_codex(self.codex, now)
                after = preferences(self.path)
                if (
                    after.get("usage_last_agent") or after.get("default_agent")
                ) != selected:
                    LOG.info('{"event":"display_usage_context_changed_during_read"}')
                    provider = after.get("usage_last_agent") or after.get(
                        "default_agent"
                    )
                    self.publish(
                        Usage(
                            provider if provider in {"codex", "claude"} else None,
                            message="Loading usage…",
                        )
                    )
                    return
                self.codex_value = Usage(
                    selected, windows, "No limits reported" if not windows else ""
                )
                self.publish(self.codex_value)
            if self.last_error:
                LOG.info('{"event":"display_usage_recovered"}')
            self.last_error = None
        except (
            OSError,
            ValueError,
            KeyError,
            TypeError,
            AttributeError,
            OverflowError,
        ) as exc:
            reason = (
                type(exc).__name__ if not isinstance(exc, UsageReadError) else str(exc)
            )
            if reason != self.last_error:
                LOG.warning(
                    json.dumps({"event": "display_usage_unavailable", "reason": reason})
                )
            self.last_error = reason
            current = self.snapshot()
            # Never retain another provider's numbers when the selected context changes or becomes unreadable.
            self.publish(
                replace(current, message="Usage unavailable")
                if selected == current.provider
                else Usage(selected, message="Usage unavailable")
            )
            if selected == "codex":
                self.codex_value = self.snapshot()

    def run(self):
        while not self.stop.is_set():
            self.refresh(datetime.now(UTC), time.monotonic())
            self.stop.wait(3)
