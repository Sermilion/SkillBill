package skillbill.engine.featuretask.slot.plan

internal const val PREPLAN_DIGEST_AUTHORITY: String =
  "The preplan digest is this phase's only repository knowledge: preplan discovers once for every " +
    "subtask so planning never repeats it. Do not read, search, grep, or list repository source, tests, " +
    "build files, or git history, and do not re-verify the digest. Settle every open question from the " +
    "digest's evidence. When the digest lacks a fact, decide from what it does say and record that " +
    "assumption in the affected spec or plan step for implement to confirm."

internal const val PLANNING_INSTALL_PROHIBITION: String =
  "Never include installer, uninstall, or install-sync commands in a spec or plan: do not plan to run " +
    "`./install.sh`, `./uninstall.sh`, `skill-bill install`, `skill-bill install apply`, or any equivalent " +
    "install refresh. Planning defines how future acceptance work will be implemented and validated; it " +
    "does not require that work to have already happened, and a later implementation or validation " +
    "action that is not yet complete never blocks planning."
