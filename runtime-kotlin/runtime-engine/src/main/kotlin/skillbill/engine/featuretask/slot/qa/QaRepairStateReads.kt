package skillbill.engine.featuretask.slot.qa

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.runloop.core.containableOrThrow
import skillbill.engine.featuretask.slot.state.PhaseQaStepBinding
import skillbill.workflow.taskruntime.model.qa.QaRepairState

internal fun FeatureTaskRuntimeRunFacts.qaSubtaskId(): Int = goalContinuation?.subtaskId ?: 1

internal fun PhaseQaStepBinding.repairStateOrInitial(subtaskId: Int): QaRepairState =
  qaRepairState() ?: QaRepairState(subtaskId)

internal fun PhaseQaStepBinding.readRepairState(subtaskId: Int): Result<QaRepairState> =
  runCatching { repairStateOrInitial(subtaskId) }.onFailure { it.containableOrThrow() }
