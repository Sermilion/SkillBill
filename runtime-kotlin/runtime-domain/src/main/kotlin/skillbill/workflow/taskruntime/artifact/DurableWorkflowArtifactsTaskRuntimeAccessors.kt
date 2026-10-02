package skillbill.workflow.taskruntime.artifact

import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationFieldAdoption
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.phaseartifacts.decomposeTerminalFrom
import skillbill.workflow.taskruntime.phaseartifacts.goalContinuationFieldAdoptionFrom
import skillbill.workflow.taskruntime.phaseartifacts.operatorBlockRetryFrom
import skillbill.workflow.taskruntime.phaseartifacts.phaseLedgerFrom
import skillbill.workflow.taskruntime.phaseartifacts.phaseRecordsFrom
import skillbill.workflow.taskruntime.phaseartifacts.resolvedBranchFrom
import skillbill.workflow.taskruntime.phaseartifacts.reviewGenerationFrom

fun DurableWorkflowArtifacts.phaseRecords(): Map<String, FeatureTaskRuntimePhaseRecord> = phaseRecordsFrom(this)

fun DurableWorkflowArtifacts.phaseLedger(): List<FeatureTaskRuntimePhaseLedgerEntry> = phaseLedgerFrom(this)

fun DurableWorkflowArtifacts.resolvedBranch(): FeatureTaskRuntimeResolvedBranch? = resolvedBranchFrom(this)

fun DurableWorkflowArtifacts.reviewGeneration(): Int = reviewGenerationFrom(this)

fun DurableWorkflowArtifacts.operatorBlockRetry(): FeatureTaskRuntimeOperatorBlockRetry? = operatorBlockRetryFrom(this)

fun DurableWorkflowArtifacts.goalContinuationFieldAdoption(): FeatureTaskRuntimeGoalContinuationFieldAdoption? =
  goalContinuationFieldAdoptionFrom(this)

fun DurableWorkflowArtifacts.decomposeTerminal(): FeatureTaskRuntimeDecomposeTerminal? = decomposeTerminalFrom(this)
