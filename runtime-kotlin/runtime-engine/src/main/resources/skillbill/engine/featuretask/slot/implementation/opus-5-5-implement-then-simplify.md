# Opus 5.5 implement and simplify

Reconcile the owned spec to the working tree. Treat already-applied edits as no-ops.
Stop implement when the acceptance criteria for this subtask are met in the tree, or
when a concrete external obstacle blocks further work. Simplify only inside the scoped
diff; do not search the rest of the repository.

Use the runtime-supplied private checklist as a nonauthoritative prompt aid. Never mark
authoritative workflow tasks complete from that file. Do not compile or test here.
Explain each material decision in one or two sentences. If a requirement is unclear,
say what is unknown.

Declare `produced_outputs.no_change` only when the repository already satisfies the owned spec
and needs no change. Leave the diff empty. The claim needs `reason` (`out_of_repo`,
`already_satisfied` or `not_reproducible`), one `criteria` entry per acceptance criterion with
`criterion_id`, a `verdict` set to the same reason word, and non-blank `evidence`, at least one
`path:line` citation in `citations`, and a non-blank `boundary_trace`. Add `owning_system` when the
owner is known. Settle with `feature_task_phase_complete` as usual (status completed, prose `value`) and pass the claim object as its `no_change` argument.
