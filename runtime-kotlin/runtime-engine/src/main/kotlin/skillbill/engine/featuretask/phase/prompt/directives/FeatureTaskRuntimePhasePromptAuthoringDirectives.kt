package skillbill.engine.featuretask.phase.prompt.directives

const val PROJECT_AUTHORING_DISCIPLINE_HEADING: String = "## Project authoring discipline (discover before write)"

fun projectAuthoringDisciplineDirective(): String =
  """
  $PROJECT_AUTHORING_DISCIPLINE_HEADING
  Discovery. Before the first code write, and again whenever owned or repair scope changes, discover what
  governs the owned files: repository and nested instructions (root and nearer AGENTS or CONTRIBUTING-style
  files), formatter and linter configuration including nested and per-module overrides, build or package
  scripts, wrappers, hooks, CI requirements, and project-selected tools and pinned versions. Resolve
  applicability per owned file, language, and module using the project's own rules. While editing, follow the
  configured naming, imports, layout, file and method limits, complexity, and other static-analysis principles.
  Reading configuration for owned paths is narrow discovery, not a repository-wide search.

  Safe commands. Run an applicable project-selected formatter or standalone static-analysis command only after
  you establish its actual input and rewrite scope and its transitive task, dependency, hook, wrapper, and
  bootstrap behavior. A lint or format name is not proof of safety. Prefer project-supported file-scoped
  invocations and never invent flags. Never compile, build, execute tests, or run a full repository check:
  build owns compile and build proof, and validate owns tests and full validation. Never format broadly and
  then revert collateral edits; inspect resulting changes for scope compliance. Repair in-scope findings in this
  session and rerun the safe command before completion. Use the command a nested module declares for its own
  files, run it from that module's working directory with that module's pinned tool and version, and run
  commands per module in a mixed-language tree instead of one root command. When no module-scoped invocation
  exists or one cannot be proven safe, record nonexecution.

  Deferral. Record nonexecution with a concrete reason, never as passed, for a missing tool, ambiguous or
  conflicting configuration, compilation-coupled analysis, unsafe bootstrap or install behavior, or a command
  that cannot preserve owned scope. Resolve nested and mixed-language rules per file and module. Preserve
  unrelated dirt and report out-of-scope findings without editing them. Never weaken rules, add suppressions or
  baselines, skip or remove required tests, or substitute project-selected tools or versions. An unavailable
  check defers to its existing owning phase, but known repairable in-scope findings cannot be resolved by
  deferral.

  Evidence. Keep a bounded record in this phase's existing output fields, never a new field. Each record names
  the configuration sources and selected version, the exact candidate command and working directory, the owned
  scope and safety basis, the actual outcome or nonexecution reason, remaining findings, and the owning phase
  for deferred work. No raw transcripts or diff hunks. If evidence cannot fit, state the limitation explicitly.
  tests_executed stays empty.
  """.trimIndent()
