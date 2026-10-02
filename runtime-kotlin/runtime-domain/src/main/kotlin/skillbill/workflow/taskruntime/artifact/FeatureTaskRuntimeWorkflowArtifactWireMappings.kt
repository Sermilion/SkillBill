package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.JsonCodec
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationFieldAdoption
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeImplementationAttemptRecordToWire
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeImplementationAttemptsFromWire
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeRunInvariantsFromArtifactMap
import skillbill.workflow.taskruntime.model.persistence.toArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeDeliveredProjectionRecord

fun FeatureTaskRuntimeImplementationAttempt.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun decodeImplementationAttemptFromArtifact(raw: Any?): FeatureTaskRuntimeImplementationAttempt? =
  JsonCodec.anyToStringAnyMap(raw)?.let(FeatureTaskRuntimeImplementationAttempt::fromArtifactMap)

fun decodeImplementationAttemptsFromArtifact(raw: Any?): List<FeatureTaskRuntimeImplementationAttempt> =
  featureTaskRuntimeImplementationAttemptsFromWire(raw)

fun Any.toWorkflowArtifactMap(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(this)

fun implementationAttemptRecordWorkflowArtifact(
  attempts: List<FeatureTaskRuntimeImplementationAttempt>,
): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(featureTaskRuntimeImplementationAttemptRecordToWire(attempts))

fun FeatureTaskRuntimeRunInvariants.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun decodeRunInvariantsFromArtifact(raw: Any?): FeatureTaskRuntimeRunInvariants? =
  JsonCodec.anyToStringAnyMap(raw)?.let { featureTaskRuntimeRunInvariantsFromArtifactMap(it) }

fun FeatureTaskRuntimeResolvedBranch.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun FeatureTaskRuntimeDecomposeTerminal.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun FeatureTaskRuntimeGoalContinuationArtifact.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun decodeGoalContinuationArtifactFromArtifact(raw: Any?): FeatureTaskRuntimeGoalContinuationArtifact? =
  JsonCodec.anyToStringAnyMap(raw)?.let(FeatureTaskRuntimeGoalContinuationArtifact::fromArtifactMap)

fun FeatureTaskRuntimeGoalContinuationFieldAdoption.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun FeatureTaskRuntimeGoalPlanningImport.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun FeatureTaskRuntimeDeliveredProjectionRecord.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun decodeDeliveredProjectionRecordFromArtifact(raw: Any?): FeatureTaskRuntimeDeliveredProjectionRecord? =
  JsonCodec.anyToStringAnyMap(raw)?.let(FeatureTaskRuntimeDeliveredProjectionRecord::fromArtifactMap)
