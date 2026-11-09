"""A portrait adaptation of the Skill Bill tray popover."""

from functools import lru_cache
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from usage import Usage

WIDTH, HEIGHT = 320, 480
ROWS_PER_PAGE = 3
BG = "#1d1d21"
TEXT = "#efeff1"
MUTED = "#a2a2ac"
LINE = "#35353e"
GOLD = "#edb94b"
ORANGE = "#f18047"
RED = "#f06565"
GREEN = "#4fca70"


@lru_cache
def font(size, bold=False):
    name = "NotoSans-Bold.ttf" if bold else "NotoSans-Regular.ttf"
    candidates = [
        Path("/usr/share/fonts/noto") / name,
        Path("/usr/share/fonts/truetype/noto") / name,
    ]
    fallback = "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
    candidates += [
        Path("/usr/share/fonts/TTF") / fallback,
        Path("/usr/share/fonts/truetype/dejavu") / fallback,
    ]
    for path in candidates:
        if path.exists():
            return ImageFont.truetype(str(path), size)
    raise RuntimeError(
        "Install Noto Sans or DejaVu Sans fonts before starting the display"
    )


def text(draw, xy, value, size=14, fill=TEXT, bold=False, width=None):
    face = font(size, bold)
    value = str(value)
    if width:
        original = value
        while value and draw.textlength(value, font=face) > width:
            value = value[:-1]
        if value != original:
            while value and draw.textlength(value + "…", font=face) > width:
                value = value[:-1]
            value += "…"
    draw.text(xy, value, font=face, fill=fill)


def pages(board):
    result = []
    if board:
        for group in ("Goals", "Phases", "Operations"):
            rows = [r for r in board.rows if r.group == group]
            result.extend(
                (group, rows[i : i + ROWS_PER_PAGE])
                for i in range(0, len(rows), ROWS_PER_PAGE)
            )
    return result or [("Your work", [])]


def render(board, now, page_index=0, error=None, usage=None):
    image = Image.new("RGB", (WIDTH, HEIGHT), BG)
    draw = ImageDraw.Draw(image)
    icon = Path(__file__).with_name("skill_bill.png")
    if not icon.exists():
        icon = (
            Path(__file__).resolve().parents[2]
            / "desktop/core/designsystem/src/commonMain/composeResources/drawable/skill_bill.png"
        )
    if icon.exists():
        with Image.open(icon) as source:
            logo = source.convert("RGBA").resize((28, 28), Image.Resampling.LANCZOS)
            image.paste(logo, (16, 17), logo)
    text(draw, (54, 16), "Skill Bill", 23, bold=True)
    clock = now.astimezone().strftime("%H:%M")
    clock_x = 304 - int(draw.textlength(clock, font=font(30, True)))
    text(draw, (clock_x, 12), clock, 30, bold=True)
    draw.line((16, 61, 304, 61), fill=LINE)
    values = [board.running, board.needs_you, board.finished] if board else ["–"] * 3
    for x, value, label, color in zip(
        (16, 118, 220),
        values,
        ("Running", "Needs you", "Finished"),
        (GOLD, ORANGE, TEXT),
    ):
        text(draw, (x, 72), value, 29, color, True, 87)
        text(draw, (x, 111), label, 13, MUTED)
    text(draw, (220, 130), "today", 11, MUTED)
    draw.line((16, 151, 304, 151), fill=LINE)
    all_pages = pages(board)
    group, rows = all_pages[page_index % len(all_pages)]
    count = sum(r.group == group for r in board.rows) if board else 0
    text(draw, (16, 163), f"{group}  {count}" if rows else group, 16, bold=True)
    text(
        draw,
        (266, 165),
        f"{page_index % len(all_pages) + 1}/{len(all_pages)}",
        12,
        MUTED,
        width=40,
    )
    if not rows:
        text(
            draw,
            (16, 238),
            "Status unavailable"
            if error
            else "All clear"
            if board
            else "Loading your work",
            21,
            bold=True,
        )
        text(
            draw,
            (16, 274),
            "Retrying automatically"
            if error
            else "No live runs"
            if board
            else "Reading Skill Bill status",
            14,
            MUTED,
        )
    for index, row in enumerate(rows):
        top = 204 + index * 55
        color = (
            GREEN
            if row.state == "active"
            else ORANGE
            if row.state in {"blocked", "failed", "idle"}
            else MUTED
        )
        draw.ellipse((17, top + 7, 25, top + 15), fill=color)
        has_progress = row.progress and row.progress[1] > 0
        text(
            draw, (35, top), row.key, 17, bold=True, width=130 if has_progress else 175
        )
        text(
            draw,
            (222, top + 3),
            row.duration(board.observed_at if error else now),
            13,
            MUTED,
            width=85,
        )
        text(draw, (35, top + 25), row.repository, 13, MUTED, width=90)
        phase_x = 35 + int(min(90, draw.textlength(row.repository, font=font(13)))) + 14
        text(draw, (phase_x, top + 25), row.phase, 13, color, width=304 - phase_x)
        if has_progress:
            text(
                draw,
                (177, top + 3),
                f"{row.progress[0]}/{row.progress[1]}",
                12,
                MUTED,
                width=38,
            )
    if error:
        text(draw, (16, 360), "Skill Bill status is stale", 10, ORANGE, width=288)
    elif board and board.warnings:
        text(
            draw, (16, 360), "Partial status · check service log", 10, ORANGE, width=288
        )
    draw_usage(draw, usage or Usage(), now, page_index)
    return image


def draw_usage(draw, usage, now, page_index):
    draw.line((16, 376, 304, 376), fill=LINE)
    title = (
        f"{usage.provider.capitalize()} limits" if usage.provider else "Usage limits"
    )
    text(draw, (16, 383), title, 15, bold=True, width=146)
    windows = usage.windows
    if not windows:
        text(draw, (16, 419), usage.message, 13, MUTED, width=288)
        return
    observed = min(w.observed_at for w in windows).astimezone()
    stamp = observed.strftime(
        "%H:%M" if observed.date() == now.astimezone().date() else "%a %H:%M"
    )
    text(
        draw,
        (175, 386),
        f"{'Saved' if usage.message else 'Read'} {stamp}",
        10,
        MUTED,
        width=130,
    )
    count = (len(windows) + 1) // 2
    start = page_index % count * 2
    for index, window in enumerate(windows[start : start + 2]):
        top = 408 + index * 34
        expired = window.reset_at is not None and window.reset_at <= now
        value = "Awaiting update" if expired else f"{window.used:g}% used"
        color = (
            MUTED
            if expired
            else RED
            if window.used >= 80
            else GOLD
            if window.used >= 50
            else GREEN
        )
        text(draw, (16, top), window.label, 12, MUTED, width=91)
        text(draw, (112, top), value, 12, color, width=96)
        if window.reset_at:
            remaining = int((window.reset_at - now).total_seconds())
            reset = (
                "now"
                if expired
                else f"in {max(1, remaining // 60)}m"
                if remaining < 3600
                else f"in {remaining // 3600}h {remaining % 3600 // 60}m"
                if remaining < 86400
                else f"in {remaining // 86400}d {remaining % 86400 // 3600}h"
            )
            text(draw, (218, top), reset, 11, MUTED, width=86)
        draw.rounded_rectangle((16, top + 22, 304, top + 25), radius=2, fill=LINE)
        if not expired and window.used > 0:
            draw.rounded_rectangle(
                (16, top + 22, 16 + max(2, int(288 * window.used / 100)), top + 25),
                radius=2,
                fill=color,
            )
