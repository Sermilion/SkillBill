package skillbill.ports.work.model

import skillbill.workflow.decomposition.model.DecompositionDependency

data class WorkGoalDependency(val subtaskId: Int, val dependency: DecompositionDependency)

data class WorkGoalDependencyAnchor(val subtaskId: Int, val dependencyId: Int)

data class WorkGoalDependencyPage(val entries: List<WorkGoalDependency>, val nextOffset: Int?)
