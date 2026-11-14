package skillbill.engine.featuretask.model.execution

import skillbill.config.model.applyValidationGateGradleWrapper
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeExecutionPlanSchemaError
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import java.util.Collections

private const val PACK_IDENTITY_LENGTH_LIMIT = 128

public data class EffectiveGatePolicyInputs(
  public val commandFamily: ValidationGateCommandFamily,
  public val packSlug: String?,
  public val declaration: ValidationGateDeclaration?,
  public val gradleWrapper: String?,
  public val validationDepth: ValidationDepth,
  public val phaseTimeoutMillis: Long?,
  public val projectCheckSnapshot: ProjectCheckCommandSnapshot? = null,
) {
  public fun gateCommandsPolicyRevision(): Int =
    if (projectCheckSnapshot != null) GATE_COMMANDS_SNAPSHOT_REVISION else 1

  internal companion object {
    internal const val GATE_COMMANDS_SNAPSHOT_REVISION = 2
    val PACK_IDENTITY = Regex("[a-z0-9][a-z0-9-]*")
  }

  public fun frozen(): EffectiveGatePolicyInputs =
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

  public fun commandArgv(role: ValidationGateCyclePhase): List<String>? =
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
    requireValidPackIdentity(packSlug)
    requireValidTimeout()
    return projectCheckSnapshot?.let(::snapshotCanonicalInputs) ?: declarationCanonicalInputs()
  }

  private fun snapshotCanonicalInputs(snapshot: ProjectCheckCommandSnapshot): List<Any?> {
    requireValidPackIdentity(snapshot.parserMetadata?.packSlug)
    return listOf(
      commandFamily.name,
      snapshot.parserMetadata?.packSlug,
      validationDepth.wireValue,
      phaseTimeoutMillis,
      snapshot.canonicalIdentity(),
    )
  }

  private fun declarationCanonicalInputs(): List<Any?> =
    listOf(
      commandFamily.name,
      packSlug,
      validationDepth.wireValue,
      phaseTimeoutMillis,
      declaration?.let(::declarationCanonicalGate),
    )

  private fun declarationCanonicalGate(gate: ValidationGateDeclaration): List<Any?> =
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

  private fun requireValidPackIdentity(slug: String?) {
    if (slug != null && !isValidPackIdentity(slug)) {
      throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError("effective policy has an invalid pack identity")
    }
  }

  private fun requireValidTimeout() {
    if (phaseTimeoutMillis != null && phaseTimeoutMillis < 0) {
      throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError("effective policy has a negative timeout")
    }
  }

  private fun isValidPackIdentity(slug: String): Boolean =
    slug.length in 1..PACK_IDENTITY_LENGTH_LIMIT && slug.matches(PACK_IDENTITY)

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
