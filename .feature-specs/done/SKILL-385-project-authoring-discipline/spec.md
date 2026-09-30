# SKILL-385 - project-authoring-discipline

## Mode

decomposed

## Intended Outcome

SKILL-385 adds project formatting and static-analysis discipline to code authoring across arbitrary platforms. Before the first code write, an authoring session discovers the repository instructions, nested formatter and linter configuration, build or package scripts, CI requirements, and project-selected tools and versions applicable to its owned files. It follows the actual naming, import, layout, file-size, method-size, complexity, and other configured rules while editing. Discovery follows file, language, and module boundaries and repeats when repair scope changes.

The guidance applies to implementation, scoped simplification, audit_implement_fix, inline review's same-session repairs, and implement_fix, including retries and continuations. Sessions run project-selected formatter and standalone static-analysis commands only after establishing their actual input and rewrite scope and the behavior of dependencies, hooks, wrappers, and bootstrap steps. Safe authoring commands cannot compile, build, execute tests, or invoke a full repository check suite. Sessions repair in-scope findings and rerun the safe command before completion. They preserve unrelated work and report out-of-scope findings without editing it.

Unavailable tools, unclear configuration, compilation-coupled commands, and commands that cannot preserve owned scope produce truthful, bounded handoff evidence. Deferred work never counts as passed. Compilation and build proof remain with build; tests and full project validation remain with validate. The change does not weaken rules, add suppressions or baselines, skip required tests, substitute tools or versions, or grant broader mutation authority.

Use existing strategy-owned prompt sections and the shared composer. Cover inline review's direct directive launch explicitly. Add no discovery executor, command runner, state owner, workflow phase, new runtime authority, schema, or contract version for this guidance. Reuse existing output and observability owners. Prompt and launch regressions prove guidance delivery and preserved runtime boundaries; they do not prove that arbitrary agents obey every external project's tools.

Preparation is local and specification-only. The runtime writes the governed parent and child specs and schema-valid manifest from this package. Planning does not alter the branch, install output, commits, workflows, or the live SKILL-384 run.

## Overview

SKILL-385 adds project formatting and static-analysis discipline to code authoring across arbitrary platforms. Before the first code write, an authoring session discovers the repository instructions, nested formatter and linter configuration, build or package scripts, CI requirements, and project-selected tools and versions applicable to its owned files. It follows the actual naming, import, layout, file-size, method-size, complexity, and other configured rules while editing. Discovery follows file, language, and module boundaries and repeats when repair scope changes.

The guidance applies to implementation, scoped simplification, audit_implement_fix, inline review's same-session repairs, and implement_fix, including retries and continuations. Sessions run project-selected formatter and standalone static-analysis commands only after establishing their actual input and rewrite scope and the behavior of dependencies, hooks, wrappers, and bootstrap steps. Safe authoring commands cannot compile, build, execute tests, or invoke a full repository check suite. Sessions repair in-scope findings and rerun the safe command before completion. They preserve unrelated work and report out-of-scope findings without editing it.

Unavailable tools, unclear configuration, compilation-coupled commands, and commands that cannot preserve owned scope produce truthful, bounded handoff evidence. Deferred work never counts as passed. Compilation and build proof remain with build; tests and full project validation remain with validate. The change does not weaken rules, add suppressions or baselines, skip required tests, substitute tools or versions, or grant broader mutation authority.

Use existing strategy-owned prompt sections and the shared composer. Cover inline review's direct directive launch explicitly. Add no discovery executor, command runner, state owner, workflow phase, new runtime authority, schema, or contract version for this guidance. Reuse existing output and observability owners. Prompt and launch regressions prove guidance delivery and preserved runtime boundaries; they do not prove that arbitrary agents obey every external project's tools.

Preparation is local and specification-only. The runtime writes the governed parent and child specs and schema-valid manifest from this package. Planning does not alter the branch, install output, commits, workflows, or the live SKILL-384 run.

## Acceptance Criteria

1. SKILL-385 Project formatting and static-analysis discipline during code authoring. Prepare only a governed specification bundle in the current base checkout /home/sermilion/StudioProjects/skill-bill-base-380 on base/SKILL-380-phase-slot-strategies. The user wants a generic solution for any platform: before implementation writes code, discover the current project's applicable repository instructions, formatter and linter configuration, build or package scripts, and CI requirements for the files being changed. Follow those actual rules while editing, including naming, imports, layout, file and method limits, complexity, and other configured static-analysis principles. Use project-selected tools and versions, without hard-coded platform lists or assumptions about Spotless, Detekt, Gradle, Kotlin, or any other particular tool. Spotless and Detekt in this repository are motivating examples. Apply the discipline to implementation, simplification, audit repairs, and code-review repairs so later edits preserve it. Run safe formatter and standalone static-analysis commands during authoring when they respect the owned file scope and do not compile, build, execute tests, or invoke a full repository check suite. Repair their in-scope findings in the same authoring session before reporting completion. Handle nested configuration, mixed languages, module-specific rules, missing tools, commands coupled to compilation, and commands that would rewrite unrelated files with explicit truthful handoff evidence using existing contracts and observability owners. Do not weaken rules, add suppressions or baselines, skip tests, change unrelated files, or claim deferred checks passed. Full project validation, tests, and compile/build proof remain with their existing owning phases. Reuse strategy-owned prompt sections and the existing shared composer without phase-name branching, duplicate state owners, new runtime authority, or unnecessary contracts. Define bounded, independently verifiable acceptance criteria and a few valuable tests of the composed prompt and unchanged execution boundaries, including a synthetic non-Kotlin project. Read the current architecture and code principles. Create parent spec, executable subtask spec or specs, and decomposition manifest through this planning phase. Do not implement, change branch, commit, push, install, resume a goal, or alter the live SKILL-384 run during planning.

## Constraints

- Runtime decompose planning stop.

## Non-Goals

- None

## Validation Strategy

Only the later validate phase executes tests, fixture capture or generators, and the full applicable project checks. Discover those checks from current AGENTS.md instructions, build configuration, scripts, and CI rather than copying stale argv. Run the focused runtime-engine prompt, launch, output-retention, and slotbaseline regressions, then the required project validation suite. Preserve governed architecture, schema-parity, scope, ledger, retry, and settlement coverage. Review fixture changes for intended prompt text; do not refresh baselines to accept unrelated behavioral differences. Compile and build proof stay with the existing build owner. Authoring formatter or standalone analysis results do not settle either gate. This planning phase ran no formatter, analysis command, compilation, build, tests, generators, or full checks.
