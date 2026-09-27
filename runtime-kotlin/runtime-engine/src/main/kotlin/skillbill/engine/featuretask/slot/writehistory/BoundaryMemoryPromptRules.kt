package skillbill.engine.featuretask.slot.writehistory

internal object BoundaryMemoryPromptRules {
  const val HISTORY_ENTRY_FORMAT: String =
    "## [<date>] <feature-name>\n" +
      "Areas: <list of affected modules/packages/areas>\n" +
      "- <what changed> (1-2 lines each)\n" +
      "- <new patterns introduced or followed>\n" +
      "- <reusable components created> (mark with \"reusable\")\n" +
      "- <breaking changes or known limitations>\n" +
      "Feature flag: <name and pattern, or N/A>\n" +
      "Acceptance criteria: <count>/<count> implemented"

  const val DECISION_ENTRY_FORMAT: String =
    "## [<date>] <short decision title>\n" +
      "Context: <what situation or requirement prompted this decision — 1-2 lines>\n" +
      "Decision: <what was chosen — 1-2 lines>\n" +
      "Reason: <why this approach over alternatives — 1-3 lines>"

  const val EXCLUDED_ROOTS: String =
    "never create `agent/` under `platform-packs/` or any other root the runtime's goal-planning discovery " +
      "exclusion list denies. Planning discovery denies those roots, so memory written there is unreadable. " +
      "Write to the nearest non-excluded owning boundary instead."

  private const val ENTRY_SIZE: String =
    "Entry body at most 4096 UTF-8 bytes, measured from the `## [<date>] <title>` heading to the next such " +
      "heading (subheadings and undated `##` lines count as body). Condense, or split into separate dated entries."

  val section: String =
    listOf(
      "## Boundary history rules",
      "Derive the feature name, feature size (`SMALL` / `MEDIUM` / `LARGE`), primary module/package/area, " +
        "affected areas, feature flag (or `N/A`), and acceptance criteria coverage (`implemented/total`) from " +
        "this briefing, the spec it references, and the current diff.",
      "Write or skip:\n" +
        "- Always write for `MEDIUM` and `LARGE` features.\n" +
        "- For `SMALL`, write only if any applies: analytics events added/removed/changed (including " +
        "properties); API contracts or GraphQL schema usage changed; UI behavior changed in ways that affect " +
        "other features; breaking changes to shared interfaces/contracts.\n" +
        "- Skip trivial `SMALL` changes (pure bug fixes, cosmetic tweaks, isolated additions).",
      "History entry format:\n```markdown\n$HISTORY_ENTRY_FORMAT\n```",
      "History file rules:\n" +
        "- File path: `<primary-boundary>/agent/history.md`.\n" +
        "- Forbidden, excluded roots: $EXCLUDED_ROOTS\n" +
        "- If the primary boundary is a skill source directory (`skills/<skill-name>/`), write to " +
        "`skills/agent/history.md` instead; skill source directories may contain only `content.md` and " +
        "`native-agents/`.\n" +
        "- Create the file and any missing parent directories when absent.\n" +
        "- Newest entry first; max 15 lines per entry; no fixed entry cap.\n" +
        "- $ENTRY_SIZE\n" +
        "- Keep older entries that still give reusable context; prune or merge only obsolete, redundant, or " +
        "noisy ones.\n" +
        "- No code snippets; focus on reusable context for future feature work.",
      "## Boundary decision rules",
      "`history.md` records what changed; `decisions.md` records why. Record a decision only when this change " +
        "made a non-obvious choice, special case, constraint, or trade-off whose reasoning the spec, plan, or " +
        "implementation states. Structure that stated reasoning faithfully; never invent reasoning. Write one " +
        "entry per decision.",
      "Decision entry format:\n```markdown\n$DECISION_ENTRY_FORMAT\n```\n" +
        "Optional trailing lines, only when relevant:\n" +
        "- `Alternatives considered: <what was rejected and why — 1 line>`\n" +
        "- `Revisit when: <condition that would make this decision worth re-evaluating>`\n" +
        "- `Superseded by: <new title> (<date>)`",
      "Decision format and file rules:\n" +
        "- File path: `<primary-boundary>/agent/decisions.md`.\n" +
        "- Forbidden, excluded roots: $EXCLUDED_ROOTS\n" +
        "- Create the file and any missing parent directories when absent.\n" +
        "- Newest entry first; max 10 lines per entry; no fixed entry cap.\n" +
        "- $ENTRY_SIZE\n" +
        "- No code snippets; describe patterns and choices in plain language.\n" +
        "- Keep older entries that still explain the boundary's design.",
      "Before appending a decision, read the existing `## [<date>] <title>` headings of the target " +
        "`decisions.md` and classify each against the new decision's scope, not keyword overlap:\n" +
        "- no-conflict: a different concern; leave it unchanged.\n" +
        "- fully-replaced: the new decision fully replaces this same-boundary entry and its `Reason` adds no " +
        "useful trap or rejected-path context; name it, then delete the whole entry.\n" +
        "- superseded-by-new: the new decision replaces its conclusion but its `Reason` still documents a trap " +
        "or rejected path; keep the body and append `Superseded by: <new title> (<date>)` inside the entry, " +
        "without editing its heading line.\n" +
        "Never prune decisions by age or in bulk. `history_recency_days` applies only to `history.md`.",
    ).joinToString("\n\n")
}
