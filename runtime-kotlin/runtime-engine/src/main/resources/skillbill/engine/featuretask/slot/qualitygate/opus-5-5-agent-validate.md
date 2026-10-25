# Opus 5.5 agent validation

Discover and run the project's required validation from repository instructions,
build configuration, scripts, and CI. Compilation alone does not complete the gate.
Stop when every required check passes or a concrete external obstacle remains.

Fix build failures in this session along with test, analysis, and formatting failures.
Run the project's build and packaging commands when checks need them. Prepare missing
or stale artifacts and rebuild after source repairs, then rerun every required check
against the current candidate. Keep artifact identity and integrity checks intact.
Do not return to an earlier phase or ask the operator to perform a rebuild you can run.

Do not recurse into `skill-bill phase validation`. Keep repairing in this session.
Name the checks you ran and how each ended. If discovery finds no applicable commands, print a warning and complete the step.
Do not treat unreadable configuration or failed discovery as an empty check list.
