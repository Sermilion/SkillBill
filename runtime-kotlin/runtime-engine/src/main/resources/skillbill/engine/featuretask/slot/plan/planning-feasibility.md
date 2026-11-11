## Implementability check

Before calling a digest, bundle or subtask plan implementation-ready, verify each
acceptance criterion against concrete evidence and its proposed production path.
Keep this check proportional to the change, but do not defer facts that determine
whether the behavior can be implemented to an implementation or repair worker.

The preplan digest is starting evidence. A plan may verify a missing implementability
fact through a targeted read-only source or authoritative contract lookup when the
task cannot be specified without it. Do not use a lookup to repeat discovery or widen
the task's file ownership.

- Identify the prerequisite capability and its owner. Distinguish existing behavior,
  a new local change authorized by the task, and an external capability the task
  does not authorize. A new local interface is valid when its implementation and
  consumers are planned together. It cannot supply information an external system
  never exposes.
- Keep a concise component inventory in the existing digest and implementation
  plan. For each required component, name its owner and whether it exists, needs
  an authorized local implementation or extension, or depends on an unavailable
  external capability. Cite source for existing behavior. For local work, assign
  a concrete implementation task and its consuming criteria. A missing converter,
  contract, persistence path or integration is work to plan when the task
  authorizes building it. Its absence alone is not a blocker or evidence that an
  earlier subtask failed.
- Count that local work in criterion sizing before fixing subtask boundaries,
  including implementation, caller wiring, contracts and required migrations.
  Assign each shared prerequisite one implementation owner and order it before
  its dependent behavior. If prerequisites make a criterion exceed the sizing
  limits, split the work under the existing decomposition rules while preserving
  usable intermediate states. Never omit prerequisite work from estimates because
  its files or symbols do not exist yet. Keep test obligations separate.
- Reconcile the inventory against the final tasks before declaring the plan ready.
  Every authorized missing component must have an implementation owner and
  dependency order. The implementation plan must identify which components the
  worker creates, so it does not require those components to exist before starting.
  Only an unresolved external prerequisite or scope or requirement conflict needs
  an operator decision. This inventory uses existing planning prose, not a new
  output schema or runtime rejection gate.
- Verify external contracts through configured authoritative tools or owning source
  and documentation. Record the source and revision where available, the actual
  operation or response, identity and authorization scope, relevant success and
  failure meanings, and required lifecycle cases. A client interface, mock, test
  fixture or simulator is not proof that the production service supplies a
  capability. Check simulator parity for behavior the plan depends on. Protected
  admin controls remain test controls unless the production contract says otherwise.
  An unavailable preferred tool is not a blocker when another authorized source
  answers the question.
- Trace required decisions through current production results and consumers. If a
  Boolean, generic error or stored state loses information the proposed behavior
  needs, plan the local result or persistence change and its affected consumers
  before the behavior that depends on it. Do not infer remote state from missing
  local data or assume undocumented idempotency, fields or operations.
- Compare criteria, constraints, non-goals, dependencies, implementation steps and
  validation expectations across the bundle. Resolve incompatible timings, retry
  classifications, status precedence, ownership and phase authority. A scheduler
  wakeup bound is different from network completion. Name the chosen rule once
  and apply it to every task and scenario that depends on it.
- For every criterion, retain a concise evidence-to-change trace in the digest and
  resulting spec or Implementation Details. Name the supporting source, the owned
  change, any prerequisite task and how later validation can observe the outcome.
  Check the final tasks against that trace before reporting completion. Planning
  inspection does not establish build, test or QA success.

Separate an implementation detail the worker can choose from an unknown capability
or conflicting product requirement. The latter prevents an executable plan for the
affected criterion. Never manufacture a capability, silently drop a requirement,
expand backend scope, or leave an impossible task for implementation to discover.
First apply any operator decision already supplied. If a product or scope decision
is still needed, report the exact contradiction or unsupported prerequisite with
source evidence and a supported alternative during planning. Preserve the original
requirement and the decision in traceability. An operator-authorized deferral must
remain explicitly unclaimed and must not block the supported scope again. Preserve
immutable parent input and sibling file ownership; report a conflict that needs an
owner's amendment rather than changing those files without authority.
