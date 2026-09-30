package skillbill.engine.featuretask.phase.core

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupRunner
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeLifecycleTelemetry
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.prepare.FeatureTaskRuntimeSpecGate

@Inject
class FeatureTaskRuntimePhaseGates(
  branch: FeatureTaskRuntimePhaseGateBranchBoundaries,
  validation: FeatureTaskRuntimePhaseGateValidationBoundaries,
) {
  val branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner = branch.branchSetupRunner
  val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner = branch.decompositionPlanner
  val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder = branch.decomposeTerminalRecorder
  val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry = branch.lifecycleTelemetry
  val gitOperations = branch.gitOperations
  val specGate: FeatureTaskRuntimeSpecGate = branch.specGate
  val buildReceiptValidator = validation.buildReceiptValidator
  val validationGateResolver = validation.validationGateResolver
  val validationGateRunner = validation.validationGateRunner
  val validationGateCoordinator = validation.validationGateCoordinator
  val readinessGateCoordinator = validation.readinessGateCoordinator
  val buildGateCoordinator = validation.buildGateCoordinator
  val sharedEvidenceResolver = validation.sharedEvidenceResolver
  val diffResolver = validation.diffResolver
  val specIntentProjectionResolver = validation.specIntentProjectionResolver
  val findingVerificationBoundaryMemory = validation.findingVerificationBoundaryMemory
}
