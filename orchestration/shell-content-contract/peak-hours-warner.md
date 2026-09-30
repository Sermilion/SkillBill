# Peak-Hours Warner

This sidecar applies to `/skill-bill` and the feature-task runtime launch
surface.

After any required update check and before intake, confirmation, workflow open,
or child continuation, read the optional peak-hours operator config from the
machine-global config at the default skill-bill config file under the user config
directory (or the
`SKILL_BILL_CONFIG_PATH` override).

If the config file is absent, or if it does not contain a `peak_hours` section,
skip this warning silently. Do not print a notice, infer fallback defaults, or
ask the user to configure peak-hour windows.

The machine-global config is JSON; the `peak_hours` value uses these fields:

```json
{
  "peak_hours": {
    "timezone": "<iana-timezone>",
    "windows": [
      { "start": "<local-start-time>", "end": "<local-end-time>" }
    ],
    "matches": [
      { "provider": "<provider-label>", "model": "<model-label>" }
    ]
  }
}
```

The `timezone` value defines how the configured windows are interpreted. Each
window is an inclusive start and exclusive end in that timezone. A match applies
when the visible provider and model metadata for a launch path match a configured
provider/model label.

If the current local time is outside every configured window, do nothing.

If the current local time is inside a configured window, inspect every
agent/model path that this launch is about to use:

- the currently executing agent and model
- `--agent`
- `--agent-override`
- each `--phase-agent <phase-id>=<agent-id>` entry

Match only against provider/model labels configured in the machine-global
config. Do not infer a model family from an agent name alone.

When at least one launch path is known to match the configured provider/model
labels, tell the user:

```text
Configured peak-hour window is active; this launch uses a configured provider/model match.
```

When different launch paths use different agents, include the known matching
path or paths in the same notice, for example
`matched path: --phase-agent review=<agent-id>`.
