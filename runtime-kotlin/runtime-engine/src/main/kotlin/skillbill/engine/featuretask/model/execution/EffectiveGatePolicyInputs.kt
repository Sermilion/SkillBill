package skillbill.engine.featuretask.model.execution

import skillbill.config.model.applyValidationGateGradleWrapper
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeExecutionPlanSchemaError
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import java.util.Collections

private const val PACK_IDENTITY_LENGTH_LIMIT = 128

data class EffectiveGatePolicyInputs(
  val commandFamily: ValidationGateCommandFamily,
  val packSlug: String?,
  val declaration: ValidationGateDeclaration?,
  val gradleWrapper: String?,
  val validationDepth: ValidationDepth,
  val phaseTimeoutMillis: Long?,
) {
  fun frozen(): EffectiveGatePolicyInputs =
    copy(
      declaration =
        declaration?.let { gate ->
          gate.copy(
            fullGateCommand = immutable(gate.fullGateCommand),
            cacheBypassingFullGateCommand = immutable(gate.cacheBypassingFullGateCommand),
            collectAllFullGateCommand = immutable(gate.collectAllFullGateCommand),
            cacheBypassingCollectAllFullGateCommand = immutable(gate.cacheBypassingCollectAllFullGateCommand),
            buildCommand = gate.buildCommand?.let(::immutable),
            cacheBypassingBuildCommand = gate.cacheBypassingBuildCommand?.let(::immutable),
            suppressionMarkers = immutable(gate.suppressionMarkers),
            findings = gate.findings.copy(artifactGlobs = immutable(gate.findings.artifactGlobs)),
          )
        },
    )

  fun commandArgv(role: ValidationGateCyclePhase): List<String>? =
    declaration?.let { gate ->
      val argv =
        when (commandFamily) {
          ValidationGateCommandFamily.BUILD ->
            when (role) {
              ValidationGateCyclePhase.INITIAL_DISCOVERY -> gate.buildCommand
              ValidationGateCyclePhase.POST_REPAIR_VERIFY -> gate.cacheBypassingBuildCommand
            }
          ValidationGateCommandFamily.VALIDATION ->
            when (role) {
              ValidationGateCyclePhase.INITIAL_DISCOVERY -> gate.collectAllFullGateCommand
              ValidationGateCyclePhase.POST_REPAIR_VERIFY -> gate.cacheBypassingCollectAllFullGateCommand
            }
        }
      argv?.let { applyValidationGateGradleWrapper(it, gradleWrapper) }
    }

  private fun immutable(values: List<String>): List<String> = Collections.unmodifiableList(ArrayList(values))

  internal fun canonicalInputs(): List<Any?> {
    if (
      packSlug != null &&
      (packSlug.length !in 1..PACK_IDENTITY_LENGTH_LIMIT || !packSlug.matches(Regex("[a-z0-9][a-z0-9-]*")))
    ) {
      throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError("effective policy has an invalid pack identity")
    }
    if (phaseTimeoutMillis != null && phaseTimeoutMillis < 0) {
      throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError("effective policy has a negative timeout")
    }
    return listOf(
      commandFamily.name,
      packSlug,
      validationDepth.wireValue,
      phaseTimeoutMillis,
      declaration?.let { gate ->
        listOf(
          command(
            ValidationGateCyclePhase.INITIAL_DISCOVERY,
            when (commandFamily) {
              ValidationGateCommandFamily.BUILD -> gate.buildCommand
              ValidationGateCommandFamily.VALIDATION -> gate.collectAllFullGateCommand
            },
          ),
          command(
            ValidationGateCyclePhase.POST_REPAIR_VERIFY,
            when (commandFamily) {
              ValidationGateCommandFamily.BUILD -> gate.cacheBypassingBuildCommand
              ValidationGateCommandFamily.VALIDATION -> gate.cacheBypassingCollectAllFullGateCommand
            },
          ),
          gate.findings.format.wireValue,
          gate.findings.artifactGlobs.sorted(),
          gate.findings.compilerDiagnostics.format.wireValue,
          gate.findings.executedWork?.format?.wireValue,
          gate.suppressionMarkers.sorted(),
          ValidationGateFindingParseMode.COLLECT_ALL.name,
        )
      },
    )
  }

  private fun command(
    role: ValidationGateCyclePhase,
    argv: List<String>?,
  ): List<Any?> =
    listOf(
      role.name,
      if (role == ValidationGateCyclePhase.INITIAL_DISCOVERY) {
        ValidationGateCacheMode.CACHE_ELIGIBLE.wireValue
      } else {
        ValidationGateCacheMode.FORCED_FULL.wireValue
      },
      argv?.let { applyValidationGateGradleWrapper(it, gradleWrapper) },
    )
}
