package skillbill.ports.work.model

import skillbill.workflow.decomposition.model.DecompositionDependency

data class WorkGoalDependency(val subtaskId: Int, val dependency: DecompositionDependency)

data class WorkGoalDependencyPage(val entries: List<WorkGoalDependency>, val nextOffset: Int?)
