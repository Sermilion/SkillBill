package skillbill.workflow.taskruntime.model.skeleton

import java.util.Collections

enum class LaunchProviderNamespace(val wireValue: String) {
  ANTHROPIC_API("anthropic_api"),
  GOOGLE("google"),
  CLAUDE_PLATFORM_AWS("claude_platform_aws"),
  BEDROCK("bedrock"),
  OPAQUE("opaque"),
  ;

  companion object {
    fun fromWire(value: String): LaunchProviderNamespace? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class LaunchModelProvenance(val wireValue: String) {
  REQUESTED_EXACT("requested_exact"),
  PROVIDER_REMAPPED("provider_remapped"),
  ALIAS_PINNED_BY_ENVIRONMENT("alias_pinned_by_environment"),
  UNRESOLVED("unresolved"),
  ;

  companion object {
    fun fromWire(value: String): LaunchModelProvenance? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class LaunchModelUnknownReason(val wireValue: String) {
  FLAG_FREE_DEFAULT("flag_free_default"),
  UNPINNED_ALIAS("unpinned_alias"),
  OPAQUE_PROVIDER("opaque_provider"),
  ENVIRONMENT_NOT_INHERITED("environment_not_inherited"),
  UNSUPPORTED_AGENT("unsupported_agent"),
  ;

  companion object {
    fun fromWire(value: String): LaunchModelUnknownReason? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class PhaseModelProfile(val wireValue: String) {
  CANONICAL("canonical"),
  OPUS_5_5("opus-5-5"),
  ;

  companion object {
    fun fromWire(value: String): PhaseModelProfile? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class LaunchEnvironmentKind(val wireValue: String) {
  INHERITED("inherited"),
  GOVERNED_CHILD("governed_child"),
}

data class EffectiveLaunchModel(
  val requestedModel: String?,
  val requestedEffort: String?,
  val effectiveModel: String?,
  val namespace: LaunchProviderNamespace,
  val provenance: LaunchModelProvenance,
  val unknownReason: LaunchModelUnknownReason?,
  val profile: PhaseModelProfile,
) {
  init {
    requestedModel?.let { require(it.isNotBlank() && LAUNCH_MODEL_TOKEN_PATTERN.matches(it)) }
    requestedEffort?.let { require(it.isNotBlank()) }
    effectiveModel?.let { require(it.isNotBlank() && LAUNCH_MODEL_TOKEN_PATTERN.matches(it)) }
    require(profile == PhaseModelProfileClassifier.classify(namespace, effectiveModel))
    if (provenance == LaunchModelProvenance.UNRESOLVED) {
      require(unknownReason != null)
    } else {
      require(unknownReason == null)
    }
  }
}

data class StepLaunchAssignment(
  val stepId: String,
  val agentId: String,
  val launch: EffectiveLaunchModel,
) {
  init {
    require(stepId.isNotBlank())
    require(agentId.isNotBlank())
  }

  val profile: PhaseModelProfile get() = launch.profile
}

object PhaseModelProfileClassifier {
  const val ANTHROPIC_OPUS_55: String = "claude-opus-5-5"
  const val BEDROCK_OPUS_55: String = "anthropic.claude-opus-5-5"

  fun classify(
    namespace: LaunchProviderNamespace,
    effectiveModel: String?,
  ): PhaseModelProfile {
    val model = effectiveModel ?: return PhaseModelProfile.CANONICAL
    return when (namespace) {
      LaunchProviderNamespace.ANTHROPIC_API,
      LaunchProviderNamespace.GOOGLE,
      LaunchProviderNamespace.CLAUDE_PLATFORM_AWS,
      ->
        if (model == ANTHROPIC_OPUS_55) PhaseModelProfile.OPUS_5_5 else PhaseModelProfile.CANONICAL
      LaunchProviderNamespace.BEDROCK ->
        if (model == BEDROCK_OPUS_55) PhaseModelProfile.OPUS_5_5 else PhaseModelProfile.CANONICAL
      LaunchProviderNamespace.OPAQUE -> PhaseModelProfile.CANONICAL
    }
  }
}

fun passThroughLaunchModel(
  requestedModel: String?,
  requestedEffort: String?,
): EffectiveLaunchModel {
  val unresolved = requestedModel == null
  return EffectiveLaunchModel(
    requestedModel = requestedModel,
    requestedEffort = requestedEffort,
    effectiveModel = requestedModel,
    namespace = LaunchProviderNamespace.OPAQUE,
    provenance = if (unresolved) LaunchModelProvenance.UNRESOLVED else LaunchModelProvenance.REQUESTED_EXACT,
    unknownReason = if (unresolved) LaunchModelUnknownReason.FLAG_FREE_DEFAULT else null,
    profile = PhaseModelProfile.CANONICAL,
  )
}

fun boundedSafeModelIdentity(value: String): String =
  if (LAUNCH_MODEL_TOKEN_PATTERN.matches(value)) value else "<redacted:${value.length}>"

const val OPUS_55_STRATEGY_ID_SUFFIX: String = "-opus-5-5"

fun immutableStepLaunchAssignments(assignments: Map<String, StepLaunchAssignment>): Map<String, StepLaunchAssignment> =
  Collections.unmodifiableMap(
    LinkedHashMap(
      assignments.mapValues { (stepId, assignment) ->
        require(assignment.stepId == stepId)
        assignment
      },
    ),
  )

val LAUNCH_MODEL_TOKEN_PATTERN: Regex = Regex("^[A-Za-z0-9._:/@=\\[\\]-]{1,128}$")
