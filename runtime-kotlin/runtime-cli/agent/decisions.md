## [2026-10-10] Hide top-level update-check instead of aliasing it
Context: SKILL-414 subtask 3 makes `skill-bill operation update-check` canonical and removes the top-level update-check from root help while keeping old invocations working.
Decision: Keep UpdateCheckCommand registered with hiddenFromHelp = true. Do not add a SkillBillCommand.aliases() entry that maps update-check to operation update-check.
Reason: operation update-check appends an `Operation invocation ID:` line. CliRuntimeUpdateTest and McpSystemToolsTest decode the top-level command's JSON payload, so an alias would change its output.
Revisit when: operation output drops the invocation-ID line, or no caller decodes the top-level update-check payload.

## [2026-10-10] Root goal routing uses a shared intake-token predicate
Context: routeIntake prepended goal to any unrecognized first token, so typos such as `phse review` silently became goal intakes.
Decision: Prepend goal only when looksLikeGoalIntakeToken in runtime-contracts matches (issue-key prefix, URL, .feature-specs/ path, or .md suffix). Other input passes through unchanged so Clikt reports the unknown command.
Reason: Putting the predicate beside TRACKER_STYLE_ISSUE_KEY_PATTERN stops the CLI from duplicating GoalIntake's grammar. The .md suffix check is a little broader than GoalIntake: notes.md routes to goal and fails there. That is accepted, and GoalIntake acceptance is unchanged.
Alternatives considered: Custom edit-distance suggestions were dropped because Clikt 5.1.0 already suggests the closest commands.

## [2026-10-05] Validate persisted add-on selections before construction
Context: SKILL-401 subtask 7 removes the CLI catch around model construction that translated IllegalArgumentException into an add-on selection usage error.
Decision: Call PersistedAgentAddonSelectionEntry.violation and AgentAddonSelection.violation before constructing models. Model invariants continue to use the same helpers.
Reason: The shared helpers keep rejection text in one place, including duplicate-slug rejection, while the CLI preserves its established UsageError prefix. True constructor defects no longer pass through an input-error catch.
