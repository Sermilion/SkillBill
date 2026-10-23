package skillbill.engine.featuretask.slot.plan

import skillbill.engine.directive.directiveResource

internal val planningFeasibilityDirective: String by lazy {
  directiveResource("/skillbill/engine/featuretask/slot/plan/planning-feasibility.md").trimEnd()
}

internal const val PREPLAN_DIGEST_AUTHORITY: String =
  "Use the preplan digest as the starting evidence; do not repeat completed discovery. " +
    "When a missing or contradictory fact determines implementability, perform only the targeted " +
    "read-only source or authoritative contract lookup needed to resolve it and record the evidence. " +
    "Do not search unrelated files or other spec bundles. Never replace a missing external capability " +
    "with an assumption for implement to confirm. Non-critical implementation choices may remain " +
    "explicit assumptions. Preserve assigned-file write boundaries."

internal const val PLANNING_INSTALL_PROHIBITION: String =
  "Never include installer, uninstall, or install-sync commands in a spec or plan: do not plan to run " +
    "`./install.sh`, `./uninstall.sh`, `skill-bill install`, `skill-bill install apply`, or any equivalent " +
    "install refresh. Planning defines how future acceptance work will be implemented and validated; it " +
    "does not require that work to have already happened, and a later implementation or validation " +
    "action that is not yet complete never blocks planning."
