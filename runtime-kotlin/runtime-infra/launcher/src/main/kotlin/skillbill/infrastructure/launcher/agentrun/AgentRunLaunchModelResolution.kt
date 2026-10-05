package skillbill.infrastructure.launcher.agentrun

import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import skillbill.workflow.taskruntime.model.skeleton.LaunchEnvironmentKind
import skillbill.workflow.taskruntime.model.skeleton.LaunchModelProvenance
import skillbill.workflow.taskruntime.model.skeleton.LaunchModelUnknownReason
import skillbill.workflow.taskruntime.model.skeleton.LaunchProviderNamespace
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfileClassifier
import skillbill.workflow.taskruntime.model.skeleton.passThroughLaunchModel

internal const val ANTHROPIC_DEFAULT_OPUS_MODEL: String = "ANTHROPIC_DEFAULT_OPUS_MODEL"

internal const val ANTHROPIC_MODEL: String = "ANTHROPIC_MODEL"

internal const val ANTHROPIC_BASE_URL: String = "ANTHROPIC_BASE_URL"

internal const val CLAUDE_CODE_USE_BEDROCK: String = "CLAUDE_CODE_USE_BEDROCK"

internal const val CLAUDE_CODE_USE_VERTEX: String = "CLAUDE_CODE_USE_VERTEX"

internal const val OPUS_ALIAS: String = "opus"

internal fun resolveAgentRunLaunchModel(
  request: AgentRunLaunchModelRequest,
  providerEnvironment: Map<String, String>,
): EffectiveLaunchModel {
  val agent =
    runCatching { SupportedAgent.fromNormalizedId(request.agentId) }.getOrNull()
      ?: return passThroughLaunchModel(request.requestedModel, request.requestedEffort)
  if (agent != SupportedAgent.CLAUDE) {
    return passThroughLaunchModel(request.requestedModel, request.requestedEffort)
  }
  return resolveClaudeLaunchModel(request, providerEnvironment)
}

private fun resolveClaudeLaunchModel(
  request: AgentRunLaunchModelRequest,
  providerEnvironment: Map<String, String>,
): EffectiveLaunchModel {
  val namespace = claudeNamespace(providerEnvironment)
  val requested = request.requestedModel
  if (requested == null) {
    return EffectiveLaunchModel(
      requestedModel = null,
      requestedEffort = request.requestedEffort,
      effectiveModel = null,
      namespace = namespace,
      provenance = LaunchModelProvenance.UNRESOLVED,
      unknownReason = LaunchModelUnknownReason.FLAG_FREE_DEFAULT,
      profile = PhaseModelProfile.CANONICAL,
    )
  }
  val remapped = remapClaudeModel(requested, providerEnvironment)
  if (remapped != requested) {
    return assignment(request, remapped, namespace, LaunchModelProvenance.PROVIDER_REMAPPED, unknownReason = null)
  }
  if (requested == OPUS_ALIAS) {
    return resolveOpusAlias(request, namespace, providerEnvironment)
  }
  return assignment(request, requested, namespace, LaunchModelProvenance.REQUESTED_EXACT, unknownReason = null)
}

internal fun remapClaudeModel(
  directive: String,
  providerEnvironment: Map<String, String>,
): String {
  val endpoint = providerEnvironment[ANTHROPIC_BASE_URL]
  if (endpoint != null && !isOfficialAnthropicEndpoint(endpoint) && isAnthropicModelReference(directive)) {
    return providerEnvironment[ANTHROPIC_MODEL]?.takeIf(String::isNotBlank) ?: directive
  }
  return directive
}

private fun resolveOpusAlias(
  request: AgentRunLaunchModelRequest,
  namespace: LaunchProviderNamespace,
  providerEnvironment: Map<String, String>,
): EffectiveLaunchModel {
  if (request.environmentKind == LaunchEnvironmentKind.GOVERNED_CHILD) {
    return assignment(
      request,
      effectiveModel = null,
      namespace,
      LaunchModelProvenance.UNRESOLVED,
      LaunchModelUnknownReason.ENVIRONMENT_NOT_INHERITED,
    )
  }
  val pinned = providerEnvironment[ANTHROPIC_DEFAULT_OPUS_MODEL]?.takeIf(String::isNotBlank)
  if (
    request.environmentKind == LaunchEnvironmentKind.INHERITED &&
    pinned != null &&
    PhaseModelProfileClassifier.classify(namespace, pinned) == PhaseModelProfile.OPUS_5_5
  ) {
    return assignment(
      request,
      pinned,
      namespace,
      LaunchModelProvenance.ALIAS_PINNED_BY_ENVIRONMENT,
      unknownReason = null,
    )
  }
  return assignment(
    request,
    effectiveModel = null,
    namespace,
    LaunchModelProvenance.UNRESOLVED,
    LaunchModelUnknownReason.UNPINNED_ALIAS,
  )
}

private fun assignment(
  request: AgentRunLaunchModelRequest,
  effectiveModel: String?,
  namespace: LaunchProviderNamespace,
  provenance: LaunchModelProvenance,
  unknownReason: LaunchModelUnknownReason?,
): EffectiveLaunchModel =
  EffectiveLaunchModel(
    requestedModel = request.requestedModel,
    requestedEffort = request.requestedEffort,
    effectiveModel = effectiveModel,
    namespace = namespace,
    provenance = provenance,
    unknownReason = unknownReason,
    profile = PhaseModelProfileClassifier.classify(namespace, effectiveModel),
  )

private fun claudeNamespace(providerEnvironment: Map<String, String>): LaunchProviderNamespace =
  when {
    truthy(providerEnvironment[CLAUDE_CODE_USE_BEDROCK]) -> LaunchProviderNamespace.BEDROCK
    truthy(providerEnvironment[CLAUDE_CODE_USE_VERTEX]) -> LaunchProviderNamespace.GOOGLE
    else -> LaunchProviderNamespace.ANTHROPIC_API
  }

private fun truthy(value: String?): Boolean =
  value.equals("1", ignoreCase = true) || value.equals("true", ignoreCase = true)
