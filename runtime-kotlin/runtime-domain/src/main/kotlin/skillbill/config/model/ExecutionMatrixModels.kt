package skillbill.config.model

import skillbill.install.model.SupportedAgent
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds

const val EXECUTION_MATRIX_KEY: String = "execution_matrix"

enum class ExecutionTier(
  val id: String,
) {
  REASONING("reasoning"),
  IMPLEMENTATION("implementation"),
  ;

  companion object {
    fun fromId(id: String): ExecutionTier? = entries.firstOrNull { it.id == id }
  }
}

internal val DEFAULT_PHASE_TIERS: Map<String, ExecutionTier> =
  mapOf(
    FeatureTaskRuntimePhaseIds.PREPLAN to ExecutionTier.IMPLEMENTATION,
    FeatureTaskRuntimePhaseIds.PLAN to ExecutionTier.REASONING,
    FeatureTaskRuntimePhaseIds.IMPLEMENT to ExecutionTier.IMPLEMENTATION,
    FeatureTaskRuntimePhaseIds.SIMPLIFY to ExecutionTier.IMPLEMENTATION,
    FeatureTaskRuntimePhaseIds.IMPLEMENT_FIX to ExecutionTier.IMPLEMENTATION,
    FeatureTaskRuntimePhaseIds.REVIEW to ExecutionTier.REASONING,
    FeatureTaskRuntimePhaseIds.VERIFY_FINDINGS to ExecutionTier.REASONING,
    FeatureTaskRuntimePhaseIds.BUILD to ExecutionTier.IMPLEMENTATION,
    FeatureTaskRuntimePhaseIds.AUDIT_PLAN_FIX to ExecutionTier.REASONING,
    FeatureTaskRuntimePhaseIds.AUDIT_IMPLEMENT_FIX to ExecutionTier.IMPLEMENTATION,
    FeatureTaskRuntimePhaseIds.AUDIT to ExecutionTier.REASONING,
    FeatureTaskRuntimePhaseIds.VALIDATE to ExecutionTier.REASONING,
    FeatureTaskRuntimePhaseIds.WRITE_HISTORY to ExecutionTier.IMPLEMENTATION,
    FeatureTaskRuntimePhaseIds.COMMIT_PUSH to ExecutionTier.IMPLEMENTATION,
    FeatureTaskRuntimePhaseIds.PR to ExecutionTier.IMPLEMENTATION,
  )

data class PhaseModelDirective(
  val model: String,
  val effort: String? = null,
) {
  init {
    require(model.isNotBlank()) { "PhaseModelDirective.model must be non-blank." }
    effort?.let { require(it.isNotBlank()) { "PhaseModelDirective.effort must be non-blank when provided." } }
  }
}

data class ExecutionMatrix(
  val phaseTiers: Map<String, ExecutionTier> = emptyMap(),
  val agents: Map<SupportedAgent, Map<ExecutionTier, PhaseModelDirective>> = emptyMap(),
  val agentPhaseOverrides: Map<SupportedAgent, Map<String, PhaseModelDirective>> = emptyMap(),
) {
  fun tierOf(phaseId: String): ExecutionTier = phaseTiers[phaseId] ?: DEFAULT_PHASE_TIERS.getValue(phaseId)

  fun directiveFor(
    agentId: String,
    phaseId: String,
  ): PhaseModelDirective? {
    val agent = SupportedAgent.entries.firstOrNull { it.id == agentId.trim().lowercase() } ?: return null
    return agentPhaseOverrides[agent]?.get(phaseId) ?: agents[agent]?.get(tierOf(phaseId))
  }
}

sealed interface ExecutionMatrixParse {
  data class Valid(val matrix: ExecutionMatrix) : ExecutionMatrixParse

  data class Invalid(
    val keyPath: String,
    val value: String,
    val reason: String,
  ) : ExecutionMatrixParse
}

fun parseExecutionMatrix(raw: Any?): ExecutionMatrixParse =
  executionMatrixInvalid(raw) ?: ExecutionMatrixParse.Valid(buildExecutionMatrix(raw))

private fun executionMatrixInvalid(raw: Any?): ExecutionMatrixParse.Invalid? {
  val matrix = raw as? Map<*, *> ?: return invalidExecutionMatrix(EXECUTION_MATRIX_KEY, raw, "must be a mapping.")
  val fields = fieldsOf(matrix)
  val unsupported = fields.entries.firstOrNull { (key, _) -> key !in EXECUTION_MATRIX_FIELDS }
  return when {
    unsupported != null ->
      invalidExecutionMatrix(
        "$EXECUTION_MATRIX_KEY.${unsupported.key}",
        unsupported.value,
        "is not a supported execution_matrix field.",
      )

    PHASE_TIERS_KEY in fields -> phaseTiersInvalid(fields[PHASE_TIERS_KEY]) ?: agentsInvalid(fields[AGENTS_KEY])
    else -> agentsInvalid(fields[AGENTS_KEY])
  }
}

private fun fieldsOf(map: Map<*, *>): Map<String, Any?> =
  map.entries.associate { (key, value) -> key.toString() to value }

private fun buildExecutionMatrix(raw: Any?): ExecutionMatrix {
  val fields = fieldsOf(checkNotNull(raw as? Map<*, *>) { "validated execution matrix must be a mapping." })
  val phaseTiers = if (PHASE_TIERS_KEY in fields) buildPhaseTiers(fields[PHASE_TIERS_KEY]) else emptyMap()
  val agents = buildAgents(fields[AGENTS_KEY])
  return ExecutionMatrix(
    phaseTiers = phaseTiers,
    agents = agents.mapValues { (_, directives) -> directives.tiers },
    agentPhaseOverrides = agents.mapValues { (_, directives) -> directives.phases }.filterValues { it.isNotEmpty() },
  )
}

private fun phaseTiersInvalid(raw: Any?): ExecutionMatrixParse.Invalid? {
  val phaseTiers =
    raw as? Map<*, *> ?: return invalidExecutionMatrix(
      "$EXECUTION_MATRIX_KEY.$PHASE_TIERS_KEY",
      raw,
      "must be a mapping.",
    )
  return phaseTiers.entries.firstNotNullOfOrNull { (rawPhaseId, rawTier) -> phaseTierInvalid(rawPhaseId, rawTier) }
}

private fun phaseTierInvalid(
  rawPhaseId: Any?,
  rawTier: Any?,
): ExecutionMatrixParse.Invalid? {
  val path = "$EXECUTION_MATRIX_KEY.$PHASE_TIERS_KEY.$rawPhaseId"
  return when {
    rawPhaseId !is String || rawPhaseId !in FeatureTaskRuntimePhaseIds.all ->
      invalidExecutionMatrix(path, rawTier, "is not a runtime phase.")

    ExecutionTier.fromId(rawTier as? String ?: "") == null ->
      invalidExecutionMatrix(path, rawTier, "must be reasoning or implementation.")

    else -> null
  }
}

private fun buildPhaseTiers(raw: Any?): Map<String, ExecutionTier> {
  val phaseTiers = checkNotNull(raw as? Map<*, *>) { "validated phase_tiers must be a mapping." }
  return phaseTiers.entries.associate { (rawPhaseId, rawTier) ->
    checkNotNull(rawPhaseId as? String) { "validated phase id must be a string." } to
      checkNotNull(ExecutionTier.fromId(rawTier as? String ?: "")) { "validated tier must be known." }
  }
}

private class AgentDirectives(
  val tiers: Map<ExecutionTier, PhaseModelDirective>,
  val phases: Map<String, PhaseModelDirective>,
)

private fun agentsInvalid(raw: Any?): ExecutionMatrixParse.Invalid? {
  val agents =
    raw as? Map<*, *> ?: return invalidExecutionMatrix(
      "$EXECUTION_MATRIX_KEY.$AGENTS_KEY",
      raw,
      "must be a mapping.",
    )
  return agents.entries.firstNotNullOfOrNull { (rawAgentId, rawTiers) -> agentInvalid(rawAgentId, rawTiers) }
}

private fun agentInvalid(
  rawAgentId: Any?,
  rawTiers: Any?,
): ExecutionMatrixParse.Invalid? {
  val path = "$EXECUTION_MATRIX_KEY.$AGENTS_KEY.$rawAgentId"
  if (rawAgentId !is String || SupportedAgent.entries.none { it.id == rawAgentId }) {
    return invalidExecutionMatrix(path, rawTiers, "is not a supported install agent.")
  }
  val entries = rawTiers as? Map<*, *> ?: return invalidExecutionMatrix(path, rawTiers, "must be a mapping.")
  return entries.entries.firstNotNullOfOrNull { (rawKey, rawDirective) ->
    agentDirectiveEntryInvalid(path, rawKey, rawDirective)
  }
}

private fun agentDirectiveEntryInvalid(
  agentPath: String,
  rawKey: Any?,
  rawDirective: Any?,
): ExecutionMatrixParse.Invalid? {
  val path = "$agentPath.$rawKey"
  return if (rawKey is String && (ExecutionTier.fromId(rawKey) != null || rawKey in FeatureTaskRuntimePhaseIds.all)) {
    directiveInvalid(path, rawDirective)
  } else {
    invalidExecutionMatrix(path, rawDirective, "must be reasoning, implementation, or a runtime phase.")
  }
}

private fun buildAgents(raw: Any?): Map<SupportedAgent, AgentDirectives> {
  val agents = checkNotNull(raw as? Map<*, *>) { "validated agents must be a mapping." }
  return agents.entries.associate { (rawAgentId, rawTiers) ->
    val agentId = checkNotNull(rawAgentId as? String) { "validated agent id must be a string." }
    val agent = checkNotNull(SupportedAgent.entries.firstOrNull { it.id == agentId }) { "validated agent must exist." }
    agent to parseAgentDirectives(rawTiers)
  }
}

private val COLLIDING_PHASE_IDS: List<String> =
  FeatureTaskRuntimePhaseIds.all.filter { ExecutionTier.fromId(it) != null }

private fun parseAgentDirectives(raw: Any?): AgentDirectives {
  require(COLLIDING_PHASE_IDS.isEmpty()) {
    "Runtime phase ids $COLLIDING_PHASE_IDS collide with execution-tier ids, so an agent's " +
      "per-phase override key would silently resolve as a tier directive."
  }
  val entries = checkNotNull(raw as? Map<*, *>) { "validated agent directives must be a mapping." }
  val tiers = mutableMapOf<ExecutionTier, PhaseModelDirective>()
  val phases = mutableMapOf<String, PhaseModelDirective>()
  entries.forEach { (rawKey, rawDirective) ->
    val key = checkNotNull(rawKey as? String) { "validated directive key must be a string." }
    val tier = ExecutionTier.fromId(key)
    val directive = buildDirective(rawDirective)
    if (tier != null) tiers[tier] = directive else phases[key] = directive
  }
  return AgentDirectives(tiers = tiers, phases = phases)
}

private fun directiveInvalid(
  path: String,
  raw: Any?,
): ExecutionMatrixParse.Invalid? {
  val directive = raw as? Map<*, *> ?: return invalidExecutionMatrix(path, raw, "must be a mapping.")
  val fields = fieldsOf(directive)
  val effort = fields[EFFORT_KEY]
  val unsupported = fields.entries.firstOrNull { (key, _) -> key !in DIRECTIVE_FIELDS }
  return when {
    MODEL_KEY !in fields -> invalidExecutionMatrix("$path.$MODEL_KEY", "<missing>", "is required.")
    (fields[MODEL_KEY] as? String).isNullOrBlank() ->
      invalidExecutionMatrix("$path.$MODEL_KEY", fields[MODEL_KEY], "must be a non-blank string.")

    EFFORT_KEY in fields && (effort !is String || effort.isBlank()) ->
      invalidExecutionMatrix("$path.$EFFORT_KEY", effort, "must be a non-blank string when provided.")

    unsupported != null ->
      invalidExecutionMatrix(
        "$path.${unsupported.key}",
        unsupported.value,
        "is not a supported model directive field.",
      )

    else -> null
  }
}

private fun buildDirective(raw: Any?): PhaseModelDirective {
  val fields = fieldsOf(checkNotNull(raw as? Map<*, *>) { "validated directive must be a mapping." })
  val model = checkNotNull(fields[MODEL_KEY] as? String) { "validated model must be a string." }
  return PhaseModelDirective(model = model, effort = fields[EFFORT_KEY] as? String)
}

private fun invalidExecutionMatrix(
  keyPath: String,
  value: Any?,
  reason: String,
): ExecutionMatrixParse.Invalid =
  ExecutionMatrixParse.Invalid(keyPath = keyPath, value = value?.toString() ?: "null", reason = reason)

private const val PHASE_TIERS_KEY: String = "phase_tiers"
private const val AGENTS_KEY: String = "agents"
private const val MODEL_KEY: String = "model"
private const val EFFORT_KEY: String = "effort"
private val EXECUTION_MATRIX_FIELDS: Set<String> = setOf(PHASE_TIERS_KEY, AGENTS_KEY)
private val DIRECTIVE_FIELDS: Set<String> = setOf(MODEL_KEY, EFFORT_KEY)
