package skillbill.engine.featuretask.slot.plan

internal const val PREPLAN_DIGEST_AUTHORITY: String =
  "The preplan digest is this phase's only repository knowledge: preplan discovers once for every " +
    "subtask so planning never repeats it. Do not read, search, grep, or list repository source, tests, " +
    "build files, or git history, and do not re-verify the digest. Settle every open question from the " +
    "digest's evidence. When the digest lacks a fact, decide from what it does say and record that " +
    "assumption in the affected spec or plan step for implement to confirm."
