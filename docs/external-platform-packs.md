# External platform packs

External platform packs let you author or register a full `platform.yaml` pack
outside the Skill Bill repository. A registered pack with the same slug as a
bundled pack replaces that bundled definition for discovery, install planning,
review composition, native-agent inventory, and quality gates. Replacement is
whole-pack: shadowed bundled content is not merged in.

Machine-global registration uses `external_platform_pack_sources` in the same
durable config document as `external_addon_sources`. Each entry is
`{"path": "<pack-root>"}` pointing at a directory that contains `platform.yaml`.
The `platform` field inside the manifest is the slug authority and must match
the pack directory name.

New full-pack scaffolds include a required local `code-quality` specialist.
That rubric is delivered in the review launch with the specialist's language
and framework guidance. A legacy custom pack without a local owner uses the
installed generic code-quality specialist when one is available. If neither
owner is installed, review keeps its compatibility behavior and records the
omission. Local pack specialists install as unlisted sidecars and native-agent
inputs; `/skill-bill` remains the only listed skill. Replacing a bundled slug
replaces the entire pack, so registration does not fill missing local quality
files from the bundled version.

## Config path precedence

1. `SKILL_BILL_CONFIG_PATH` when set (supports `~` expansion)
2. `~/.config/skill-bill/config.json`
3. `~/.skill-bill/config.json` (legacy)

## Register an existing pack

```bash
export SKILL_BILL_CONFIG_PATH="$HOME/tmp/skill-bill-external-pack.json"
skill-bill config register-external-platform-pack --path ~/dev/company-packs/kotlin --repo-root /path/to/skill-bill
skill-bill config resolve-external-platform-packs --repo-root /path/to/skill-bill
```

`--dry-run` validates the pack and prints the canonical path that would be
registered without writing config.

## Unregister

```bash
skill-bill config unregister-external-platform-pack --path ~/dev/company-packs/kotlin
```

Unregister removes only the config entry; your source directory is untouched.
Reinstall after unregister restores the bundled pack when the checkout still
ships that slug.

## Scaffold a new external pack

Use an isolated config path so the example does not edit your developer config.
`pack_location_path` is the pack root. `pack_registration` is `create` (default)
or `register`. External create scaffolds only under that path, then registers
the canonical path after the tree is durable. `register` validates an existing
tree and does not rewrite `platform.yaml`. A blank path keeps the in-repo
scaffold under `platform-packs/<slug>` and does not register it. `--dry-run`
prints the planned path and writes neither files nor config.

```bash
export SKILL_BILL_CONFIG_PATH="$HOME/tmp/skill-bill-external-pack.json"
mkdir -p "$HOME/tmp"
cat > "$HOME/tmp/my-team-pack.json" <<'EOF'
{
  "scaffold_payload_version": "1.0",
  "kind": "platform-pack",
  "platform": "my-team",
  "display_name": "My Team",
  "description": "Review pack for My Team services.",
  "routing_signals": { "strong": [".my-team"] },
  "pack_location_path": "~/dev/company-packs/my-team",
  "pack_registration": "create"
}
EOF
skill-bill new --payload "$HOME/tmp/my-team-pack.json" --dry-run
skill-bill new --payload "$HOME/tmp/my-team-pack.json"
```

## Author, validate, install, update

The scaffold writes `platform.yaml` and `content.md` under the external root.
`skill-bill show`, `skill-bill fill`, `skill-bill edit`, and
`skill-bill validate` resolve the skill name through the effective catalog, so
a registered override is the file they read and write. Install from a Skill
Bill checkout. Planning reads the same catalog, and a later edit of the
external `content.md` is picked up by the next install. A pack without
`validation_gate` still fails with `MissingValidationGateError` when it wins
quality-check routing.

```bash
export SKILL_BILL_CONFIG_PATH="$HOME/tmp/skill-bill-external-pack.json"
skill-bill show bill-my-team-code-review --repo-root /path/to/skill-bill
skill-bill fill bill-my-team-code-review --body-file "$HOME/tmp/my-team-content.md" --repo-root /path/to/skill-bill
skill-bill validate --skill-name bill-my-team-code-review --repo-root /path/to/skill-bill
```

```bash
skill-bill install apply
skill-bill config resolve-external-platform-packs --repo-root /path/to/skill-bill
```

Resolve lines are `slug`, source kind `external` or `bundled`, the shadowed
bundled slug or `-`, and the canonical root. The command does not write config.

## Unregister

```bash
skill-bill config unregister-external-platform-pack --path ~/dev/company-packs/my-team
skill-bill install apply
```

Unregister removes only the config entry. Reinstall restores the bundled pack
when the checkout still ships that slug, and removes managed output for a slug
that exists only as an external pack.

## Kotlin replacement

Point `pack_location_path` at a directory named `kotlin` whose `platform.yaml`
declares `platform: kotlin`. Registration replaces the bundled kotlin pack as a
whole. Resolve lists `kotlin`, source kind `external`, and shadowed bundled
slug `kotlin`. Config order and routing scores do not bring the bundled
definition back. Missing specialists, pointers, add-ons, native-agent
declarations, and `validation_gate` are not filled from the shadowed pack.

```bash
export SKILL_BILL_CONFIG_PATH="$HOME/tmp/skill-bill-external-pack.json"
cat > "$HOME/tmp/kotlin-pack.json" <<'EOF'
{
  "scaffold_payload_version": "1.0",
  "kind": "platform-pack",
  "platform": "kotlin",
  "display_name": "Kotlin",
  "description": "Replacement Kotlin review pack.",
  "pack_location_path": "~/dev/company-packs/kotlin",
  "pack_registration": "register"
}
EOF
skill-bill new --payload "$HOME/tmp/kotlin-pack.json" --dry-run
skill-bill config register-external-platform-pack --path ~/dev/company-packs/kotlin
skill-bill fill bill-kotlin-code-review --body-file "$HOME/tmp/kotlin-content.md" --repo-root /path/to/skill-bill
skill-bill validate --skill-name bill-kotlin-code-review --repo-root /path/to/skill-bill
skill-bill config resolve-external-platform-packs --repo-root /path/to/skill-bill
skill-bill install apply
skill-bill config unregister-external-platform-pack --path ~/dev/company-packs/kotlin
skill-bill install apply
```

The register payload expects an existing conforming tree and does not rewrite
it. After unregister, install restores bundled kotlin.

## External add-ons vs pack replacement

`external_addon_sources` overlays add-on files onto an **installed** effective
pack. Pack replacement is `external_platform_pack_sources` only. See
[External addons](external-addons.md) for overlay semantics.
