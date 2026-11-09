# Skill Bill USB display

An optional Linux companion for the Turing 3.5-inch `USB35INCHIPSV2` display.
It replaces the system-monitor dashboard with Skill Bill's machine-wide work
board. The desktop app can be closed while the display service runs.

The portrait layout adapts the tray popover. It shows Running, Needs you and
Finished today, followed by issue keys, repository names, phases, active durations
and subtask progress. Each page holds three runs, with project and status together
on the second line. Goals, phases and operations cycle every eight seconds, with empty groups omitted. The board refreshes every
ten seconds, with one bounded CLI request sequence in flight.

The companion calls `skill-bill work board --finished-within 24h` and follows all
pages. It never opens SQLite or starts a run. Its counts follow the tray's board
sections, including paused runs under Needs you and canceled work under Finished
today. Finished today uses the tray's rolling 24-hour window. Historical executions
of the same issue remain distinct. The reader supports work-board contract `1.3`
and status contract `0.6`, and refuses other versions. No persisted runtime format
changes are required.

Failed reads retain the last complete board, freeze its durations and display a
stale warning. A later valid read clears the warning. Runtime diagnostics produce
a partial-status warning. Failures and recovery appear in the service journal.
USB disconnection restarts the service, which uses the stable serial-device path.

## Contextual usage limits

The bottom panel follows `usage_last_agent` from the app's preferences at
`~/.skill-bill/desktop/settings.preferences_pb`. It shows only Codex or Claude,
retaining the app's last supported provider when the desktop app closes. It reads
that file without modifying it. Before the app has selected a provider, the panel
asks for a selection rather than guessing.

Codex uses the existing signed-in CLI account through the read-only
[`account/rateLimits/read` app-server method](https://learn.chatgpt.com/docs/app-server).
It does not start a thread or send a prompt. Reads, including failed attempts,
are at least one minute apart. The installer records the detected Codex executable
in the service command. Codex uses its normal `CODEX_HOME` environment and signed-in
account. The display does not read or copy credentials.

Claude uses the newest reading the app saved for its configured Claude profile.
These windows update after a Claude reply reports them. The display does not send
a request to Claude to obtain a reading. App preference and provider selection
changes reach the display on its next local read, every three seconds.

Each reported window shows percentage used, a bar and time until reset. More than
two windows cycle in pairs. No absent window becomes an invented 5-hour allowance.
The header shows the reading time. Failed refreshes retain the last numbers with
a Saved timestamp; a reset that has passed shows Awaiting update with no filled
bar. Data for another provider or Claude profile never supplies the fallback.
Missing, unreadable or incompatible preference data produces an unavailable
message and a journal diagnostic. Current app preferences and saved-reading `v1`
encodings remain untouched, so this change requires no data migration.

`--usage-settings <file>` selects another app preferences file and `--codex <path>`
selects the CLI executable. The screen follows the provider remembered by the app;
it does not independently infer the selected chat from running processes. Custom
per-chat Codex profiles need the same `CODEX_HOME` in the display service.

## Install

Requires Linux with a systemd user session, Python 3.12 or newer with venv and pip,
Noto Sans or DejaVu Sans fonts, an installed Skill Bill CLI, and access to the USB
serial device. The installer creates a separate virtual environment with pinned
Pillow, pyserial and smartscreen-driver dependencies. It installs no root service.

From this repository, run:

```sh
python3 scripts/usb-display/install.py --replace-system-monitor --rotate-180
```

Omit `--rotate-180` for an upright display. Use `--device /dev/serial/by-id/...`
for a different stable device path. The installer validates a live status preview
before stopping the old monitor, then sends a real frame before enabling the new
service. It disables `turzx-native-monitor.service` after starting the replacement.
The original monitor configuration stays intact. A failed switch restores the
original monitor's active and enabled states.

Installed files live under `~/.local/share/skill-bill-usb-display/`. The user unit
is `~/.config/systemd/user/skill-bill-usb-display.service`. It conflicts with the
old monitor so systemd does not run both against one serial port. Do not launch
another USB-screen writer manually while the service runs.

```sh
systemctl --user status skill-bill-usb-display.service
journalctl --user -u skill-bill-usb-display.service
```

`preview.png` in the install directory contains the last frame sent to the device.
To update, stop the service, rerun the installer and start it again.

## Restore system stats

```sh
systemctl --user disable --now skill-bill-usb-display.service
systemctl --user enable --now turzx-native-monitor.service
```

These commands preserve Skill Bill's runtime data and both service configurations.

## Develop

Use a Python environment with `requirements.txt` installed:

```sh
python -m unittest discover -s scripts/usb-display -p 'test_*.py'
python scripts/usb-display/display.py --output /tmp/skill-bill-display.png
```

The second command renders live data without opening the USB device. `--page 1`
selects the second page. `--once` sends one live frame to the screen and exits.
Run hardware commands only after stopping other display writers. The driver sends
changed image regions after the first full frame. Preview images are upright even
when the physical display uses 180-degree rotation.
