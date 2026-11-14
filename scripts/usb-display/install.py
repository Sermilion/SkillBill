#!/usr/bin/env python3
"""Install the optional Linux USB display service for the current user."""

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

SERVICE = "skill-bill-usb-display.service"
MONITOR = "turzx-native-monitor.service"


def systemctl(*args, check=True):
    return subprocess.run(
        ["systemctl", "--user", *args], check=check, capture_output=True, text=True
    )


def quote(value):
    return (
        '"'
        + str(value)
        .replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("%", "%%")
        .replace("$", "$$")
        + '"'
    )


def install(args):
    source = Path(__file__).resolve().parent
    home = Path.home()
    target = home / ".local/share/skill-bill-usb-display"
    unit = home / ".config/systemd/user" / SERVICE
    cli = shutil.which("sona")
    if not cli:
        raise RuntimeError(
            "Install Skill Bill and add it to PATH before installing the display service"
        )
    monitor_running = (
        systemctl("is-active", "--quiet", MONITOR, check=False).returncode == 0
    )
    monitor_enabled = (
        systemctl("is-enabled", "--quiet", MONITOR, check=False).returncode == 0
    )
    if (monitor_running or monitor_enabled) and not args.replace_system_monitor:
        raise RuntimeError(
            "System monitor owns the screen. Pass --replace-system-monitor to replace it."
        )
    if systemctl("is-active", "--quiet", SERVICE, check=False).returncode == 0:
        raise RuntimeError(
            "Stop skill-bill-usb-display.service before updating its files"
        )
    target.mkdir(parents=True, exist_ok=True)
    venv = target / "venv"
    subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True)
    python = venv / "bin/python"
    subprocess.run(
        [str(python), "-m", "pip", "install", "-r", str(source / "requirements.txt")],
        check=True,
    )
    for name in ("board.py", "render.py", "display.py", "usage.py", "requirements.txt"):
        shutil.copy2(source / name, target / name)
    shutil.copy2(
        source.parents[1]
        / "desktop/core/designsystem/src/commonMain/composeResources/drawable/sona.png",
        target / "sona.png",
    )
    command = [
        str(python),
        str(target / "display.py"),
        "--cli",
        cli,
        "--device",
        args.device,
    ]
    codex = shutil.which("codex")
    if codex:
        command.extend(["--codex", codex])
    if args.rotate_180:
        command.append("--rotate-180")
    # Validate the actual board and fonts before touching either service.
    subprocess.run([*command, "--output", str(target / "preview.png")], check=True)
    unit.parent.mkdir(parents=True, exist_ok=True)
    previous = unit.read_bytes() if unit.exists() else None
    previously_enabled = (
        systemctl("is-enabled", "--quiet", SERVICE, check=False).returncode == 0
    )
    contents = "\n".join(
        [
            "[Unit]",
            "Description=Skill Bill USB status display",
            f"Conflicts={MONITOR}",
            "",
            "[Service]",
            "Type=simple",
            "ExecStart="
            + " ".join(
                quote(part)
                for part in [*command, "--preview", str(target / "preview.png")]
            ),
            "Restart=on-failure",
            "RestartSec=5",
            "TimeoutStopSec=10",
            "Environment=PYTHONUNBUFFERED=1",
            "WorkingDirectory=%h",
            "",
            "[Install]",
            "WantedBy=default.target",
            "",
        ]
    )
    candidate = target / SERVICE
    candidate.write_text(contents)
    subprocess.run(["systemd-analyze", "--user", "verify", str(candidate)], check=True)
    if previous is not None:
        unit.with_suffix(".service.previous").write_bytes(previous)
    try:
        if monitor_running:
            systemctl("stop", MONITOR)
        subprocess.run([*command, "--once"], check=True)
        temporary = unit.with_suffix(".service.tmp")
        temporary.write_text(contents)
        temporary.replace(unit)
        systemctl("daemon-reload")
        systemctl("enable", "--now", SERVICE)
        systemctl("is-active", "--quiet", SERVICE)
        if monitor_enabled:
            systemctl("disable", MONITOR)
    except (OSError, RuntimeError, subprocess.CalledProcessError):
        systemctl("stop", SERVICE, check=False)
        if not previously_enabled:
            systemctl("disable", SERVICE, check=False)
        if previous is None:
            unit.unlink(missing_ok=True)
        else:
            unit.write_bytes(previous)
        systemctl("daemon-reload", check=False)
        if monitor_enabled:
            systemctl("enable", MONITOR, check=False)
        if monitor_running:
            systemctl("start", MONITOR, check=False)
        raise
    print(f"Installed {SERVICE}. Preview: {target / 'preview.png'}")
    print(f"Service log: journalctl --user -u {SERVICE}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--replace-system-monitor", action="store_true")
    parser.add_argument(
        "--device",
        default="/dev/serial/by-id/usb-Turing_UsbMonitor_USB35INCHIPSV2-if00",
    )
    parser.add_argument("--rotate-180", action="store_true")
    try:
        install(parser.parse_args())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"Display installation failed: {exc}", file=sys.stderr)
        if isinstance(exc, subprocess.CalledProcessError) and exc.stderr:
            print(exc.stderr, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
