package skillbill.ports.work.model

import skillbill.workflow.decomposition.model.DecompositionDependency

data class WorkGoalDependency(val subtaskId: Int, val dependency: DecompositionDependency)

/** The dependency a page continues strictly after, in (subtask id, dependency target id) order. */
data class WorkGoalDependencyAnchor(val subtaskId: Int, val dependencyId: Int)

data class WorkGoalDependencyPage(val entries: List<WorkGoalDependency>, val nextOffset: Int?)
