# External Addon Sources

External addon sources let you keep private or team-specific add-on content
outside this repo. Platform-pack add-ons are merged into installed packs during
install. Agent add-ons are discovered from configured external source roots at
selection time.

Use [Getting Started](getting-started.md) for install and full CLI coverage, and
[Getting Started for Teams](getting-started-for-teams.md) for the broader customization model.
External addons are the lightest way to ship an add-on that must stay outside the shared repo.
To replace an entire platform pack (not just add-ons), see [External platform packs](external-platform-packs.md).

## When To Use This

Reach for an external addon source when all of these hold:

- The add-on extends an **existing installed pack** (e.g. `ios`, `kotlin`, `python`) or an
  agent-scoped workflow through `agent-addon:<slug>`.
- The content is **private or team-specific** and should not live in the public repo.
- You want it applied automatically on install, the same way pack-owned add-ons are.

If the behavior is durable and shareable, prefer a pack-owned add-on or a forked pack instead
(see [Customization Layers](getting-started-for-teams.md#customization-layers)). External sources
are for content that deliberately stays out of tree.

## How It Works

The overlay runs automatically during `./install.sh`, **after** the installed platform packs are
reconciled from upstream and **before** skills are staged. For each configured source it:

1. Reads the machine-global config and resolves each `{ path, platform }` entry.
2. Skips (with a warning) any source whose target platform pack is not installed.
3. Validates the source's `addon-manifest.yaml` and referenced `.md` files.
4. Copies the add-on `.md` files into the installed pack's `addons/` directory.
5. Appends the add-on's `addon_usage` and `pointers` entries into the installed pack's
   `platform.yaml`.

Because the overlay always re-applies against the freshly reconciled pack, it is idempotent:
re-running install never duplicates entries, and a wiped `addons/` directory is reconstituted on
the next install.

`addon_usage` plus the matching `pointers` entries are enough to compose add-on files into
rendered native agents. A markdown link in the owning `content.md` is not required and is not the
activation trigger. Do not edit an upstream pack's `content.md` to turn an add-on on.

## Configuring Sources

Sources are declared in the machine-global config file:

- Default path: `~/.config/skill-bill/config.json` — a
  durable location that survives installs (see [Persisting Config Across Installs](#persisting-config-across-installs))
- Override with the `SKILL_BILL_CONFIG_PATH` environment variable (supports `~` expansion)

Add an `external_addon_sources` array. Platform-pack entries may use the legacy
`{ path, platform }` form or the explicit `{ kind: "platform-pack", path,
platform }` form. Agent add-on entries use `{ kind: "agent-addon", path }`:

```json
{
  "external_addon_sources": [
    { "kind": "platform-pack", "path": "~/dev/acme-review-addon", "platform": "ios" },
    { "kind": "agent-addon", "path": "~/dev/personal-agent-addons" }
  ]
}
```

| Field | Meaning |
|-------|---------|
| `kind` | Optional for legacy platform-pack entries. Supported values: `platform-pack`, `agent-addon`. |
| `path` | Source directory. Platform-pack sources contain `addon-manifest.yaml` and referenced `.md` files. Agent add-on sources contain one or more `<slug>/agent-addon.yaml` and `<slug>/content.md` directories. Supports `~`; relative paths resolve against the current working directory. Must exist and be a directory. |
| `platform` | Required for platform-pack sources. The platform-pack slug to overlay onto (e.g. `ios`). If that pack is not installed, the source is skipped with a warning, not treated as an error. Omit for `agent-addon` sources. |

For `agent-addon` sources, `path` points at a root containing one or more
agent add-on source directories:

```
personal-agent-addons/
├── codex-subagent-policy/
│   ├── agent-addon.yaml
│   └── content.md
└── execution-budget-local/
    ├── agent-addon.yaml
    └── content.md
```

`external_addon_sources` shares `config.json` with telemetry settings; other keys in the file are
left untouched.

## Persisting Config Across Installs

`config.json` — including your `external_addon_sources` — lives at the durable path
**`~/.config/skill-bill/config.json`** by default. That
path is **outside** the `~/.skill-bill/` tree that a `./install.sh` (or update) wipes during its
pre-install cleanup, so your sources survive every install with **no configuration on your part**.

Resolution order used by both the installer and the runtime:

1. `SKILL_BILL_CONFIG_PATH` — explicit override; pin the config anywhere.
2. `~/.config/skill-bill/config.json` when it exists — the durable default.
3. `~/.skill-bill/config.json` — legacy fallback for older installs.

New installs write to the durable path (#2). Existing installs are **migrated automatically**: the
installer moves a legacy `~/.skill-bill/config.json` to the durable path *before* the pre-install
cleanup, so upgrading never drops your sources. The cleanup still wipes the rest of `~/.skill-bill/`
(preserving `skills/`, `platform-packs/`, `orchestration/`, `baseline-manifest.json`, and `*.db`
state), but the config no longer lives there.

Notes:

- To keep the config somewhere else, export `SKILL_BILL_CONFIG_PATH` from your shell profile so every
  `install.sh` and `sona` invocation sees it. Without the override, the durable default applies.
  If you exported this in an earlier version solely to relocate the config to `~/.config/skill-bill/`,
  you can remove it now — the durable default is that same path.
- As a one-off, `SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 ./install.sh …` skips the wipe entirely
  (intended for dev iteration).

## Source Directory Layout

The directory referenced by `path` holds one manifest plus the add-on markdown files it references:

```
acme-review-addon/
├── addon-manifest.yaml
└── acme-review.md
```

Every `.md` file referenced by a pointer target must exist in this directory. The files are copied
verbatim into the installed pack's `addons/` directory.

You can scaffold this source shape directly:

```bash
sona new-addon --platform ios --name acme-review --addon-location-path ~/dev/acme-review-addon
```

The normal `sona new` add-on wizard also lets you choose `external` as the add-on source.
Both paths create or update `addon-manifest.yaml` in the external directory and write the skeleton
markdown file there instead of editing the repo's `platform-packs/<platform>/addons/` directory.
The installer also registers the selected directory in `external_addon_sources` so the add-on
appears in the app tree on refresh.

## addon-manifest.yaml Format

The manifest is a fragment of a platform pack's manifest: it declares `addon_usage` and `pointers`,
each keyed by a **skill-relative directory** that the target pack already declares (its baseline,
area, and quality-check dirs).

```yaml
addon_usage:
  code-review/bill-ios-code-review:
    - slug: acme
      entrypoint: acme-review.md
pointers:
  code-review/bill-ios-code-review:
    - name: acme-review.md
      target: acme-review.md
```

Rules the overlay enforces (loud-fail on violation):

- **`addon_usage` keys must be declared skill directories** of the installed pack.
- **`entrypoint` and every `companion_pointers` entry must name a pointer** declared under the same
  directory, and that pointer's target must resolve under the pack's `addons/`.
- **Pointer `name`** is a bare markdown filename (`*.md`, no path separators, no `..`).
- **Pointer `target`** is auto-rewritten to the canonical
  `platform-packs/<platform>/addons/<file>.md` form. You may write just the bare filename
  (`acme-review.md`) and it is expanded for you; a fully-qualified target is accepted as long as it
  points at the same flat location.
- **Targets must be flat** — directly under `addons/`, with no subdirectories.
- **Only `name`/`target`** are allowed on pointer entries and only
  `slug`/`entrypoint`/`companion_pointers` on addon-usage entries; unexpected keys are rejected.
- **Add-on `slug`** must match `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`.

## Worked Example

Overlay a private `acme` review add-on onto the installed `ios` pack.

1. Create the source directory:

   ```
   ~/dev/acme-review-addon/
   ├── addon-manifest.yaml
   └── acme-review.md
   ```

   `addon-manifest.yaml`:

   ```yaml
   addon_usage:
     code-review/bill-ios-code-review:
       - slug: acme
         entrypoint: acme-review.md
   pointers:
     code-review/bill-ios-code-review:
       - name: acme-review.md
         target: acme-review.md
   ```

2. Register it in `~/.config/skill-bill/config.json`:

   ```json
   {
     "external_addon_sources": [
       { "path": "~/dev/acme-review-addon", "platform": "ios" }
     ]
   }
   ```

3. Preview what will resolve (optional dry run):

   ```bash
   sona config resolve-external-addons
   # ios	/home/you/dev/acme-review-addon
   ```

4. Install. The overlay applies automatically:

   ```bash
   ./install.sh
   ```

   `acme-review.md` is copied into the installed `ios` pack's `addons/`, and its `addon_usage` and
   `pointers` entries are appended to the installed `platform.yaml`. Native-agent rendering composes
   that add-on from the declaration alone; the upstream pack's `content.md` is left unchanged.

## Commands

| Command | Purpose |
|---------|---------|
| `sona config resolve-external-addons` | Read-only. Lists resolved `platform<TAB>path` entries from the config, or fails loudly on a malformed config. |
| `sona config resolve-external-agent-addons` | Read-only. Lists resolved external agent add-on roots from the same config, or fails loudly on malformed config. |
| `sona install apply-external-addons` | Applies the overlay onto installed packs. Runs automatically during `./install.sh`; you rarely invoke it directly. Options: `--repo-root` (default `.`) and `--platform-packs` (default `<repo-root>/platform-packs`). |

## Guarantees And Failure Modes

- **Idempotent.** Re-applying never duplicates entries; wiped add-on files are restored on the next
  install.
- **Not-installed platform is skipped**, with a warning, not a failure.
- **Collisions loud-fail.** A pointer name, pointer target basename, or add-on slug that collides
  with a pack-owned entry — or with another external source targeting the same platform and skill
  directory — aborts the overlay rather than silently overwriting. Sources targeting *different*
  platforms never collide, even if they share a skill-relative directory and pointer name.
- **Validate-before-write atomicity.** All sources are validated before any file is written, so a
  validation failure in a later source leaves earlier sources unapplied and the installed manifests
  untouched. A mid-write I/O error (disk/permission) can leave a partially applied state; it
  self-heals on the next install.
- **The installed `platform.yaml` is the merge target**, re-adopted from upstream on each reconcile.
  Comments and key ordering in that installed copy are not preserved across an overlay; your source
  files and this config are the source of truth.
- **`addon_usage` alone activates composition.** Overlaying `addon_usage` and `pointers` is enough
  for rendered native agents to carry the add-on. Do not edit an upstream pack `content.md` to add
  a markdown link.
