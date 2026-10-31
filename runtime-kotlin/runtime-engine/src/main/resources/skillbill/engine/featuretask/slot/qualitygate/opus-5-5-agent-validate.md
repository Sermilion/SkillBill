# Opus 5.5 agent validation

Discover and run the project's required validation from repository instructions,
build configuration, scripts, and CI. Compilation alone does not complete the gate.
Complete when every runnable required check passes. Confirmed unavailable tooling
skips only the affected checks, with warnings naming each command or target, missing
prerequisite, and evidence. Continue independent checks and never report skipped
coverage as passed. Missing tooling alone never blocks validation, even if no checks
can run. Other unresolved external obstacles retain the existing blocked behavior.

Fix build failures in this session along with test, analysis, and formatting failures.
Run the project's build and packaging commands when checks need them. Prepare missing
or stale artifacts and rebuild after source repairs, then rerun every runnable required check
against the current candidate. Keep artifact identity and integrity checks intact.
Do not return to an earlier phase or ask the operator to perform a rebuild you can run.

Put every temporary copy, file, or directory this session creates under `$TMPDIR`. The runtime owns
that directory and deletes it when the session ends. Never create them under `~/.cache`, the
skill-bill state directory, or beside the checkout.

Do not recurse into `skill-bill phase validation`. Keep repairing in this session.
Name the checks you ran and how each ended. If discovery finds no applicable commands, print a warning and complete the step.
Do not treat unreadable configuration or failed discovery as an empty check list.

## Test probes

After every required check passes, probe at most six tests this subtask added or changed: first tests
covering a listed broken-then-repaired scenario, then one test per listed required criterion, then the
remaining changed tests in diff order. For each test, name the production line or branch it guards.
Copy the current checkout, uncommitted changes included, to a new directory under `$TMPDIR`, apply
there the smallest change that removes that behavior, run only that test, then delete
the copy. Never mutate the workflow checkout. Record killed, survived, or not_run with a reason.
Strengthen a survived test inside this existing repair session, for example by asserting the specific
outcome instead of only success, driving the named entry point instead of an internal helper, or
keeping setup alive through the assertion, then re-probe it once. Never change production code to make
a probe pass, and never weaken an assertion. Every listed repaired scenario needs one probed test that
drives its entry point; write one when none exists. When no copy can be made or no targeted test
command exists, record every probe not_run with the reason and finish on the normal checks. Report
probes in ordinary prose. For optional telemetry, prefer one line per test in the suggested grammar.
Probe formatting never blocks validation.
