## [2026-10-05] Validate persisted add-on selections before construction
Context: SKILL-401 subtask 7 removes the CLI catch around model construction that translated IllegalArgumentException into an add-on selection usage error.
Decision: Call PersistedAgentAddonSelectionEntry.violation and AgentAddonSelection.violation before constructing models. Model invariants continue to use the same helpers.
Reason: The shared helpers keep rejection text in one place, including duplicate-slug rejection, while the CLI preserves its established UsageError prefix. True constructor defects no longer pass through an input-error catch.
