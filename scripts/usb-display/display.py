#!/usr/bin/env python3
"""Publish the machine-wide Skill Bill work board to a Turing USB35INCHIPSV2."""

import argparse
import json
import logging
import shutil
import signal
import threading
import time
from datetime import UTC, datetime
from pathlib import Path

from board import BoardReadError, read_board
from render import render
from usage import UsageFeed

LOG = logging.getLogger("skill-bill-usb-display")
DEFAULT_DEVICE = "/dev/serial/by-id/usb-Turing_UsbMonitor_USB35INCHIPSV2-if00"


class Feed:
    def __init__(self, cli, stop, interval):
        self.cli, self.stop, self.interval = cli, stop, interval
        self.lock = threading.Lock()
        self.board, self.error = None, None

    def refresh(self):
        try:
            board = read_board(self.cli)
        except BoardReadError as exc:
            LOG.error(
                json.dumps({"event": "display_status_failed", "reason": str(exc)})
            )
            with self.lock:
                self.error = str(exc)
        else:
            if board.warnings:
                LOG.warning(
                    json.dumps(
                        {
                            "event": "display_status_partial",
                            "diagnostics": board.warnings,
                        }
                    )
                )
            with self.lock:
                recovered = self.error is not None
                self.board, self.error = board, None
            if recovered:
                LOG.info('{"event":"display_status_recovered"}')

    def run(self):
        while not self.stop.is_set():
            self.refresh()
            self.stop.wait(self.interval)

    def snapshot(self):
        with self.lock:
            return self.board, self.error


def save_preview(frame, destination):
    destination = Path(destination)
    temporary = destination.with_name(destination.name + ".tmp")
    frame.save(temporary, format="PNG")
    temporary.replace(destination)


def run(args):
    stop = threading.Event()
    for signum in (signal.SIGTERM, signal.SIGINT):
        signal.signal(signum, lambda *_: stop.set())
    feed = Feed(args.cli, stop, args.refresh_seconds)
    usage_feed = UsageFeed(args.usage_settings, args.codex, stop)
    if args.output or args.once:
        feed.refresh()
        board, error = feed.snapshot()
        if error:
            raise BoardReadError(error)
        usage_feed.refresh(datetime.now(UTC), time.monotonic())
        frame = render(board, datetime.now(UTC), args.page, usage=usage_feed.snapshot())
        if args.output:
            save_preview(frame, args.output)
            return
    device = Path(args.device).resolve(strict=True)
    from smartscreen_driver.lcd_comm import Orientation
    from smartscreen_driver.lcd_comm_rev_a import LcdCommRevA

    class Display(LcdCommRevA):
        def write_line(self, line):
            # Reconnect through systemd so every failed transfer gets initialization and a full repaint.
            self.serial_write(line)

    lcd = Display(com_port=str(device), display_width=320, display_height=480)
    try:
        lcd.lcd_serial.exclusive = True
        lcd.lcd_serial.write_timeout = 5
        lcd.initialize_comm()
        lcd.screen_on()
        lcd.set_brightness(args.brightness)
        lcd.set_orientation(
            Orientation.REVERSE_PORTRAIT if args.rotate_180 else Orientation.PORTRAIT
        )
        if args.once:
            lcd.paint(frame)
            LOG.info('{"event":"display_frame_sent"}')
            return
        threading.Thread(target=feed.run, daemon=True, name="skill-bill-status").start()
        threading.Thread(
            target=usage_feed.run, daemon=True, name="skill-bill-usage"
        ).start()
        started = time.monotonic()
        previous = None
        while not stop.is_set():
            board, error = feed.snapshot()
            now = datetime.now(UTC)
            page = int((time.monotonic() - started) // args.page_seconds)
            frame = render(board, now, page, error, usage=usage_feed.snapshot())
            if previous is None:
                lcd.paint(frame)
            else:
                from PIL import ImageChops

                changed = ImageChops.difference(previous, frame).getbbox()
                if changed:
                    lcd.paint(frame.crop(changed), pos=changed[:2])
            previous = frame
            if args.preview:
                save_preview(frame, args.preview)
            stop.wait(1)
    finally:
        lcd.close_serial()


def positive(value):
    number = float(value)
    if not 1 <= number <= 3600:
        raise argparse.ArgumentTypeError("Use a value between 1 and 3600 seconds")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli", default=str(Path.home() / ".local/bin/sona"))
    parser.add_argument("--device", default=DEFAULT_DEVICE)
    parser.add_argument(
        "--usage-settings",
        default=str(Path.home() / ".skill-bill/desktop/settings.preferences_pb"),
    )
    parser.add_argument(
        "--codex",
        default=shutil.which("codex"),
        help="Codex executable for read-only account limits",
    )
    parser.add_argument("--rotate-180", action="store_true")
    parser.add_argument(
        "--brightness", type=int, choices=range(101), default=100, metavar="0..100"
    )
    parser.add_argument("--refresh-seconds", type=positive, default=10)
    parser.add_argument("--page-seconds", type=positive, default=8)
    parser.add_argument("--page", type=int, default=0, help="Preview page index")
    parser.add_argument(
        "--output",
        help="Render one live snapshot to PNG without opening the USB device",
    )
    parser.add_argument("--preview", help="Also save each displayed frame to this PNG")
    parser.add_argument(
        "--once",
        action="store_true",
        help="Send one live frame to the device, then exit",
    )
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    try:
        run(args)
    except (BoardReadError, OSError, RuntimeError) as exc:
        LOG.error(json.dumps({"event": "display_failed", "reason": str(exc)}))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
