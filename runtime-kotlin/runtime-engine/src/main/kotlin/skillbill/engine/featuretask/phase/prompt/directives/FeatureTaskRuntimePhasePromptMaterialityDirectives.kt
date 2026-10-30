package skillbill.engine.featuretask.phase.prompt.directives

const val MATERIALITY_RULE: String =
  "Count a gap or defect only when it is material: a realistic user, operator, or runtime action reaches " +
    "it in normal use, and it causes a visible failure, wrong behavior, or loss of data someone relies on. " +
    "Read absolute wording such as never, every, or always as its practical intent, not as a mandate to " +
    "enumerate unreachable cases. These do not count: paths reachable only through hand-crafted, " +
    "nonsensical, or contrived input; representational differences that leave effective behavior " +
    "unchanged, such as an explicit null rewritten to the default it already meant, a duplicate key the " +
    "reader already ignored, or null versus an empty list that every reader treats alike; races that need " +
    "precisely timed concurrent writers with no practical trigger; and failures that end in a clear error " +
    "without losing data."
