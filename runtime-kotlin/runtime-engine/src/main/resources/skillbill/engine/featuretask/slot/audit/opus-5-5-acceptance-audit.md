# Opus 5.5 acceptance audit

Inspect against the accepted plan. Keep audit read-only, persist `audit_plan_fix` when
planning must change, and keep `audit_implement_fix` ordered after that. Cover every
criterion on the first pass; later passes cover only unresolved criteria. Honor receipts
and repair caps.

Do not compile or test as audit proof. Cite file and line evidence. If a criterion cannot
be judged, say it is unknown rather than passing it.
