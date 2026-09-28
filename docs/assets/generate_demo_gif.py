#!/usr/bin/env python3
"""Generate the skill-bill terminal demo GIF (scripted playback, not a live capture).

Renders a macOS-style terminal window streaming a /skill-bill run: each phase
starts (spinner) and finishes (check), the run halts at the claude -p usage limit, then
resumes from durable workflow state and completes. Pure Pillow frame-by-frame so the
style matches docs/assets/skill-bill-demo-placeholder.svg.
"""
from PIL import Image, ImageDraw, ImageFont

# ---- geometry -------------------------------------------------------------
W, H = 1000, 560
WX, WY, WW, WH = 40, 34, 920, 492          # window rect
RADIUS = 16
TITLE_H = 52
PAD_X = 34                                  # text inset from window left
BODY_TOP = WY + TITLE_H + 26
LINE_H = 42
FONT_SZ = 22
MAXLINES = 9                                # visible log lines

# ---- palette --------------------------------------------------------------
PAGE   = (11, 12, 14)
GRID   = (17, 19, 23)
WIN_BG = (15, 17, 22)
BORDER = (138, 115, 48)
SEP    = (29, 32, 38)
TITLE  = (139, 144, 153)
FG     = (201, 209, 217)
MUTED  = (110, 118, 129)
GREEN  = (63, 185, 80)
AMBER  = (226, 184, 74)
RED    = (255, 107, 95)
BLUE   = (88, 166, 255)
TL_RED, TL_YEL, TL_GRN = (255, 95, 87), (254, 188, 46), (40, 200, 64)

FONT_PATH = "/usr/share/fonts/TTF/MesloLGSNerdFontMono-Regular.ttf"
FONT_BOLD = "/usr/share/fonts/TTF/MesloLGSNerdFontMono-Bold.ttf"
font = ImageFont.truetype(FONT_PATH, FONT_SZ)
fontb = ImageFont.truetype(FONT_BOLD, FONT_SZ)
font_title = ImageFont.truetype(FONT_BOLD, 18)

SPIN = "◐◓◑◒"         # rotating circle (braille is absent from this Nerd Font face)

# ---- pacing ---------------------------------------------------------------
SPEED = 2.95          # >1 slows the demo (holds longer, more spinner rotations)
def S(ms):            # scale a hold/settle duration
    return int(ms * SPEED)
def N(n):             # scale a spinner frame count (more rotations, same fps)
    return max(n, round(n * SPEED))

# ---- frame store ----------------------------------------------------------
frames = []   # list of (PIL.Image RGB, duration_ms)

def emit(img, ms):
    frames.append((img, ms))

# ---- static window chrome (drawn once, pasted each frame) -----------------
def base_canvas():
    img = Image.new("RGB", (W, H), PAGE)
    d = ImageDraw.Draw(img)
    for x in range(0, W, 42):
        d.line([(x, 0), (x, H)], fill=GRID, width=1)
    for y in range(0, H, 42):
        d.line([(0, y), (W, y)], fill=GRID, width=1)
    # window
    d.rounded_rectangle([WX, WY, WX + WW, WY + WH], radius=RADIUS, fill=WIN_BG,
                        outline=BORDER, width=2)
    # title bar separator
    d.line([(WX + 1, WY + TITLE_H), (WX + WW - 1, WY + TITLE_H)], fill=SEP, width=1)
    # traffic lights
    cy = WY + TITLE_H // 2
    for i, col in enumerate((TL_RED, TL_YEL, TL_GRN)):
        cx = WX + 26 + i * 26
        d.ellipse([cx - 7, cy - 7, cx + 7, cy + 7], fill=col)
    # centered title
    t = "skill-bill — demo"
    tw = d.textlength(t, font=font_title)
    d.text((WX + (WW - tw) / 2, cy - 10), t, font=font_title, fill=TITLE)
    return img

BASE = base_canvas()

def render(lines, active=None, caret=False):
    """lines: list of segment-lists; each segment = (text, color, bold).
       active: an extra in-progress segment-list drawn as the bottom line.
    """
    img = BASE.copy()
    d = ImageDraw.Draw(img)
    rows = list(lines)
    if active is not None:
        rows = rows + [active]
    rows = rows[-MAXLINES:]
    y = BODY_TOP
    for row in rows:
        x = WX + PAD_X
        for (text, color, bold) in row:
            f = fontb if bold else font
            d.text((x, y), text, font=f, fill=color)
            x += d.textlength(text, font=f)
        if caret and row is rows[-1] and active is not None:
            d.rectangle([x + 2, y + 3, x + 13, y + FONT_SZ + 2], fill=FG)
        y += LINE_H
    return img

# ---- builder state --------------------------------------------------------
log = []   # settled rows

def visible_with(active):
    return log, active

def push(row):
    log.append(row)

def type_cmd(text, hold=320):
    prompt = ("$ ", GREEN, False)
    step = 1
    for n in range(0, len(text) + 1, step):
        active = [prompt, (text[:n], FG, False)]
        emit(render(log, active, caret=True), 42)
    push([prompt, (text, FG, False)])
    emit(render(log), S(hold))

def run_step(label, frames_n=6, detail=None, glyph="✓", gcol=GREEN,
             label_done=None, settle=240):
    for i in range(N(frames_n)):
        sp = SPIN[i % len(SPIN)]
        active = [(sp + " ", AMBER, False), (label, FG, False)]
        emit(render(log, active), 95)
    row = [(glyph + " ", gcol, True), (label_done or label, gcol, False)]
    if detail:
        row.append(("  " + detail, MUTED, False))
    push(row)
    emit(render(log), S(settle))

def note(segments, hold=360):
    push(segments)
    emit(render(log), S(hold))

# ---- script ---------------------------------------------------------------
type_cmd("/skill-bill .feature-specs/SKILL-81/spec.md")

run_step("assess",   frames_n=5, detail="acceptance criteria · size: M")
run_step("branch",   frames_n=4, detail="feat/SKILL-81-launch-readiness")
run_step("pre-plan", frames_n=6, detail="boundaries · digest")
run_step("plan",     frames_n=6, detail="ordered steps")
# limit failure during implement
for i in range(N(9)):
    sp = SPIN[i % len(SPIN)]
    emit(render(log, [(sp + " ", AMBER, False), ("implement", FG, False)]), 95)
push([("✗ ", RED, True), ("implement", RED, False),
      ("  interrupted", MUTED, False)])
emit(render(log), S(360))
note([("⚠ ", AMBER, True),
      ("run halted — any reason (usage limit · crash · lost connection)", MUTED, False)],
     hold=560)
note([("  state saved → ", MUTED, False), ("wfta-7f3a", AMBER, False),
      (" · resume anytime, nothing lost", MUTED, False)], hold=900)

# resume
type_cmd("skill-bill feature-task status wfta-7f3a")
note([("↻ ", BLUE, True), ("resuming from durable state · step: ", MUTED, False),
      ("implement", BLUE, False)], hold=620)
run_step("implement", frames_n=6, detail="resumed · diff applied")
run_step("review",    frames_n=6, detail="8 specialists · 0 blockers")
run_step("audit",     frames_n=5, detail="spec criteria met")
run_step("check",     frames_n=6, detail="gradlew check · green")

run_step("durable workflow state persisted", frames_n=3, gcol=AMBER, settle=240)
run_step("telemetry recorded (parent/child tree)", frames_n=3, gcol=AMBER, settle=300)
note([("$ ", GREEN, False),
      ("PR ready — spec → merged-ready, survived the limit", FG, False)], hold=3000)

# ---- save -----------------------------------------------------------------
import os, subprocess
imgs = [f[0] for f in frames]
durs = [f[1] for f in frames]
out = "docs/assets/skill-bill-demo.gif"
raw = "docs/assets/_demo_raw.gif"
imgs[0].save(raw, save_all=True, append_images=imgs[1:], duration=durs,
             loop=0, disposal=2, optimize=True)
# palette-optimize (this demo uses few colors) — frame timing is preserved
subprocess.run(["magick", raw, "-coalesce", "-layers", "OptimizeFrame",
                "-fuzz", "3%", "-colors", "64", "+dither", out], check=True)
os.remove(raw)
imgs[-1].save("docs/assets/skill-bill-demo-poster.png")
total = sum(durs)
size = os.path.getsize(out) / 1e6
print(f"frames={len(frames)} total={total/1000:.1f}s size={size:.1f}MB out={out}")
