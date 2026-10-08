# Opus 5.5 acceptance audit

Inspect against the accepted plan. Keep audit read-only, persist `audit_plan_fix` when
planning must change, and keep `audit_implement_fix` ordered after that. Cover every
criterion on the first pass; later passes cover only unresolved criteria. Honor receipts
and repair caps.

Do not compile or test as audit proof. Cite file and line evidence. If a criterion cannot
be judged, say it is unknown rather than passing it.

When the implementation output carries `produced_outputs.no_change`, the diff is expected to
be empty. Read every cited `path:line` and the boundary trace without editing anything. Do not
resolve citations outside this worktree. Answer `no_change_confirmed` when the evidence supports
every criterion's verdict. Otherwise answer `no_change_rejected` and give the specific reasons in
the prose value.
