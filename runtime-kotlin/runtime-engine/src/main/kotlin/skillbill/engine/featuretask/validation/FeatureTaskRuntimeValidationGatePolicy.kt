package skillbill.engine.featuretask.validation

import skillbill.config.model.applyValidationGateGradleWrapper
import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode

internal fun validationGateArgv(
  declaration: ValidationGateDeclaration,
  cyclePhase: ValidationGateCyclePhase,
): List<String> =
  when (cyclePhase) {
    ValidationGateCyclePhase.INITIAL_DISCOVERY -> declaration.collectAllFullGateCommand
    ValidationGateCyclePhase.POST_REPAIR_VERIFY -> declaration.cacheBypassingCollectAllFullGateCommand
  }

internal fun validationGateCommand(
  declaration: ValidationGateDeclaration,
  cyclePhase: ValidationGateCyclePhase,
  gradleWrapper: String?,
): String =
  applyValidationGateGradleWrapper(
    validationGateArgv(declaration, cyclePhase),
    gradleWrapper,
  ).joinToString(" ")

internal fun requiredValidationGateCyclePhase(
  progress: FeatureTaskRuntimeValidationGateProgress?,
): ValidationGateCyclePhase =
  if (
    progress?.gateRuns?.lastOrNull()?.cacheMode == ValidationGateCacheMode.FORCED_FULL
  ) {
    ValidationGateCyclePhase.POST_REPAIR_VERIFY
  } else {
    ValidationGateCyclePhase.INITIAL_DISCOVERY
  }

internal fun requiredValidationGateCommand(
  declaration: ValidationGateDeclaration,
  gradleWrapper: String?,
  progress: FeatureTaskRuntimeValidationGateProgress?,
): String =
  validationGateCommand(
    declaration,
    requiredValidationGateCyclePhase(progress),
    gradleWrapper,
  )

internal fun resolveRequiredValidationCommand(
  resolver: ValidationGateResolver,
  requiredCommandForDeclaration: (ValidationGateDeclaration) -> String,
  changedPaths: List<String>?,
  evidence: FeatureTaskRuntimeValidationEvidence?,
  sourceLabel: String,
): String? {
  val resolution = resolver.resolve(changedPaths.orEmpty())
  if (changedPaths == null && resolution is ValidationGateResolution.Declared) {
    throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(
      sourceLabel,
      "validation changed-path inventory is missing for a declared validation gate.",
    )
  }
  return when (resolution) {
    is ValidationGateResolution.Declared -> requiredCommandForDeclaration(resolution.declaration)
    is ValidationGateResolution.Absent -> evidence?.results?.lastOrNull()?.command
    is ValidationGateResolution.Incompatible ->
      throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(sourceLabel, resolution.reason)
  }
}
