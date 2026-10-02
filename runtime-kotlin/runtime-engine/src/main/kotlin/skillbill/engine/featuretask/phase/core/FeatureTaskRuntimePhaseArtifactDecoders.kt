package skillbill.engine.featuretask.phase.core

import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.taskruntime.artifact.decomposeTerminal
import skillbill.workflow.taskruntime.artifact.goalContinuationFieldAdoption
import skillbill.workflow.taskruntime.artifact.operatorBlockRetry
import skillbill.workflow.taskruntime.artifact.phaseLedger
import skillbill.workflow.taskruntime.artifact.phaseRecords
import skillbill.workflow.taskruntime.artifact.resolvedBranch
import skillbill.workflow.taskruntime.artifact.reviewGeneration
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationFieldAdoption
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry

internal fun decodePhaseRecords(artifacts: Map<String, Any?>): Map<String, FeatureTaskRuntimePhaseRecord> =
  DurableWorkflowArtifacts.fromMap(artifacts).phaseRecords()

internal fun resolvedBranchFromWorkflowArtifacts(artifacts: Map<String, Any?>): FeatureTaskRuntimeResolvedBranch? {
  return DurableWorkflowArtifacts.fromMap(artifacts).resolvedBranch()
}

internal fun reviewGenerationFrom(artifacts: Map<String, Any?>): Int {
  return DurableWorkflowArtifacts.fromMap(artifacts).reviewGeneration()
}

internal fun operatorBlockRetryFromWorkflowArtifacts(
  artifacts: Map<String, Any?>,
): FeatureTaskRuntimeOperatorBlockRetry? {
  return DurableWorkflowArtifacts.fromMap(artifacts).operatorBlockRetry()
}

internal fun goalContinuationFieldAdoptionFromWorkflowArtifacts(
  artifacts: Map<String, Any?>,
): FeatureTaskRuntimeGoalContinuationFieldAdoption? {
  return DurableWorkflowArtifacts.fromMap(artifacts).goalContinuationFieldAdoption()
}

internal fun decomposeTerminalFromWorkflowArtifacts(
  artifacts: Map<String, Any?>,
): FeatureTaskRuntimeDecomposeTerminal? {
  return DurableWorkflowArtifacts.fromMap(artifacts).decomposeTerminal()
}

internal fun decodePhaseLedger(artifacts: Map<String, Any?>): List<FeatureTaskRuntimePhaseLedgerEntry> {
  return DurableWorkflowArtifacts.fromMap(artifacts).phaseLedger()
}
