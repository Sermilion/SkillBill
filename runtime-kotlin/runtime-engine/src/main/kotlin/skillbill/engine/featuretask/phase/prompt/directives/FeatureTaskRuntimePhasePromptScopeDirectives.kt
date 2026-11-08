package skillbill.engine.featuretask.phase.prompt.directives

internal const val ACCEPTANCE_SUBTASK_SCOPE_RULE: String =
  "Apply the selected subtask's authored scope, non-goals, and explicit ownership boundaries before " +
    "classifying a gap or expanding a defect class. For each gap, cite the current subtask obligation " +
    "it violates. Work explicitly assigned to a later subtask is deferred context and cannot keep this " +
    "criterion open or become a required repair item. Missing engine acquisition or caller integration " +
    "alone is not a gap when that integration is explicitly deferred. Still require the current " +
    "subtask's promised contract, pure domain enforcement, serialization, and retained evidence; a later " +
    "consumer does not excuse missing prerequisite behavior. Do not invent a deferral from file " +
    "ownership or a worker claim. Reconcile prior findings and saved plans against these boundaries, " +
    "name excluded deferred work and its declared owner, and preserve all remaining in-scope gaps."
