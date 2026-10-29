package skillbill.application.config

import skillbill.application.config.model.CompactionEditField
import skillbill.application.config.model.CompactionEditIntent
import skillbill.application.config.model.ExecutionMatrixEditIntent
import skillbill.config.model.COMPACTION_KEY
import skillbill.config.model.CompactionFieldKeys
import skillbill.config.model.CompactionSettingsParse
import skillbill.config.model.EXECUTION_MATRIX_KEY
import skillbill.config.model.ExecutionMatrixFieldKeys
import skillbill.config.model.ExecutionMatrixParse
import skillbill.config.model.parseCompactionSettings
import skillbill.config.model.parseExecutionMatrix
import skillbill.ports.config.model.ConfigDocumentTree

internal fun mergeExecutionMatrixSection(
  original: ConfigDocumentTree,
  requested: Map<String, Any?>,
  intent: ExecutionMatrixEditIntent,
): MachineConfigTreeResult {
  val originalValue = original.value(EXECUTION_MATRIX_KEY)
  if (original.contains(EXECUTION_MATRIX_KEY) && originalValue !is Map<*, *>) {
    return MachineConfigTreeResult.Invalid(EXECUTION_MATRIX_KEY, "must be a mapping.")
  }
  val matrix = (originalValue as? Map<*, *>)?.stringMap() ?: linkedMapOf()
  (mergeMatrixTiers(matrix, requested, intent) ?: mergeMatrixAgents(matrix, requested, intent))?.let { return it }

  if (sectionUnchanged(
      original,
      EXECUTION_MATRIX_KEY,
      originalValue,
      matrix,
    )
  ) {
    return MachineConfigTreeResult.Valid(original)
  }
  return when (val parsed = parseExecutionMatrix(matrix)) {
    is ExecutionMatrixParse.Invalid -> MachineConfigTreeResult.Invalid(parsed.keyPath, parsed.reason)
    is ExecutionMatrixParse.Valid -> MachineConfigTreeResult.Valid(original.with(EXECUTION_MATRIX_KEY, matrix))
  }
}

private fun mergeMatrixTiers(
  matrix: MutableMap<String, Any?>,
  requested: Map<String, Any?>,
  intent: ExecutionMatrixEditIntent,
): MachineConfigTreeResult.Invalid? {
  val requestedTiers = requested[ExecutionMatrixFieldKeys.PHASE_TIERS] as? Map<*, *>
  val hadTiers = matrix.containsKey(ExecutionMatrixFieldKeys.PHASE_TIERS)
  val tiersValue = matrix[ExecutionMatrixFieldKeys.PHASE_TIERS]
  if (hadTiers && tiersValue !is Map<*, *>) {
    return MachineConfigTreeResult.Invalid(
      "$EXECUTION_MATRIX_KEY.${ExecutionMatrixFieldKeys.PHASE_TIERS}",
      "must be a mapping.",
    )
  }
  val tiers = (tiersValue as? Map<*, *>)?.stringMap() ?: linkedMapOf()
  intent.phaseTiers.forEach { edit ->
    if (requestedTiers?.containsKey(edit.phaseId) == true) {
      tiers[edit.phaseId] = requestedTiers[edit.phaseId]
    } else {
      tiers.remove(edit.phaseId)
    }
  }
  if (hadTiers || tiers.isNotEmpty()) matrix[ExecutionMatrixFieldKeys.PHASE_TIERS] = tiers

  return null
}

private fun mergeMatrixAgents(
  matrix: MutableMap<String, Any?>,
  requested: Map<String, Any?>,
  intent: ExecutionMatrixEditIntent,
): MachineConfigTreeResult.Invalid? {
  val requestedAgents = requested[ExecutionMatrixFieldKeys.AGENTS] as? Map<*, *>
  val hadAgents = matrix.containsKey(ExecutionMatrixFieldKeys.AGENTS)
  val agentsValue = matrix[ExecutionMatrixFieldKeys.AGENTS]
  if (hadAgents && agentsValue !is Map<*, *>) {
    return MachineConfigTreeResult.Invalid(
      "$EXECUTION_MATRIX_KEY.${ExecutionMatrixFieldKeys.AGENTS}",
      "must be a mapping.",
    )
  }
  val agents = (agentsValue as? Map<*, *>)?.stringMap() ?: linkedMapOf()
  val selectedAgentIds =
    (
      intent.agentTierDirectives.map {
        it.agentId
      } + intent.agentPhaseOverrides.map { it.agentId }
    ).toSet()
  for (agentId in selectedAgentIds) {
    mergeAgentDirectives(agents, requestedAgents, intent, agentId)?.let { return it }
  }
  if (hadAgents || agents.isNotEmpty() || matrix.containsKey(ExecutionMatrixFieldKeys.PHASE_TIERS)) {
    matrix[ExecutionMatrixFieldKeys.AGENTS] = agents
  }

  return null
}

private fun mergeAgentDirectives(
  agents: MutableMap<String, Any?>,
  requestedAgents: Map<*, *>?,
  intent: ExecutionMatrixEditIntent,
  agentId: String,
): MachineConfigTreeResult.Invalid? {
  val hadAgent = agents.containsKey(agentId)
  val agentValue = agents[agentId]
  if (hadAgent && agentValue !is Map<*, *>) {
    return MachineConfigTreeResult.Invalid(
      "$EXECUTION_MATRIX_KEY.${ExecutionMatrixFieldKeys.AGENTS}.$agentId",
      "must be a mapping.",
    )
  }
  val directives = (agentValue as? Map<*, *>)?.stringMap() ?: linkedMapOf()
  intent.agentTierDirectives.filter { it.agentId == agentId }.forEach { edit ->
    updateDirective(
      directives,
      requestedAgents?.get(agentId) as? Map<*, *>,
      edit.tier.id,
    )
  }
  intent.agentPhaseOverrides.filter { it.agentId == agentId }.forEach { edit ->
    updateDirective(
      directives,
      requestedAgents?.get(agentId) as? Map<*, *>,
      edit.phaseId,
    )
  }
  if (hadAgent || directives.isNotEmpty()) agents[agentId] = directives
  return null
}

internal fun mergeCompactionSection(
  original: ConfigDocumentTree,
  requested: Map<String, Any?>,
  intent: CompactionEditIntent,
): MachineConfigTreeResult {
  if (intent.isEmpty()) return MachineConfigTreeResult.Valid(original)
  return mergeNonEmptyCompactionSection(original, requested, intent)
}

private fun mergeNonEmptyCompactionSection(
  original: ConfigDocumentTree,
  requested: Map<String, Any?>,
  intent: CompactionEditIntent,
): MachineConfigTreeResult {
  val originalValue = original.value(COMPACTION_KEY)
  if (original.contains(COMPACTION_KEY) && originalValue !is Map<*, *>) {
    return MachineConfigTreeResult.Invalid(COMPACTION_KEY, "must be a mapping.")
  }
  val section = (originalValue as? Map<*, *>)?.stringMap() ?: linkedMapOf()
  (mergeCompactionGlobals(section, requested, intent) ?: mergeCompactionPhases(section, requested, intent))?.let {
    return it
  }

  if (sectionUnchanged(original, COMPACTION_KEY, originalValue, section)) return MachineConfigTreeResult.Valid(original)
  return when (val parsed = parseCompactionSettings(section)) {
    is CompactionSettingsParse.Invalid -> MachineConfigTreeResult.Invalid(parsed.keyPath, parsed.reason)
    is CompactionSettingsParse.Valid -> MachineConfigTreeResult.Valid(original.with(COMPACTION_KEY, section))
  }
}

private fun mergeCompactionGlobals(
  section: MutableMap<String, Any?>,
  requested: Map<String, Any?>,
  intent: CompactionEditIntent,
): MachineConfigTreeResult.Invalid? {
  intent.globalFields.forEach { field ->
    val key = field.key()
    if (!requested.containsKey(key)) {
      return MachineConfigTreeResult.Invalid(
        "$COMPACTION_KEY.$key",
        "the edited value is missing from the submitted section.",
      )
    }
    section[key] = requested[key]
  }

  return null
}

private fun mergeCompactionPhases(
  section: MutableMap<String, Any?>,
  requested: Map<String, Any?>,
  intent: CompactionEditIntent,
): MachineConfigTreeResult.Invalid? {
  val phaseKey = CompactionFieldKeys.PHASES
  val hadPhases = section.containsKey(phaseKey)
  val rawPhases = section[phaseKey]
  if (hadPhases && rawPhases != null && rawPhases !is Map<*, *>) {
    return MachineConfigTreeResult.Invalid("$COMPACTION_KEY.$phaseKey", "must be a mapping.")
  }
  val hasPhaseWrites = intent.addedPhaseIds.isNotEmpty() || intent.phaseFields.values.any { it.isNotEmpty() }
  val editingNullPhases = hadPhases && rawPhases == null
  if (editingNullPhases && hasPhaseWrites && !intent.hasAddedPhases()) {
    return MachineConfigTreeResult.Invalid(
      "$COMPACTION_KEY.$phaseKey",
      "is explicitly null. Replace it with a mapping before adding or editing a phase override.",
    )
  }
  val requestedPhases = requested[phaseKey] as? Map<*, *>
  val phases = (rawPhases as? Map<*, *>)?.stringMap() ?: linkedMapOf()
  mergePhaseEdits(phases, requestedPhases, intent)?.let { return it }
  intent.removedPhaseIds.forEach(phases::remove)
  if (rawPhases is Map<*, *> || hasPhaseWrites) section[phaseKey] = phases

  return null
}

private fun mergePhaseEdits(
  phases: MutableMap<String, Any?>,
  requestedPhases: Map<*, *>?,
  intent: CompactionEditIntent,
): MachineConfigTreeResult.Invalid? {
  val phaseKey = CompactionFieldKeys.PHASES
  for (phaseId in intent.addedPhaseIds) {
    val requestedPhase =
      requestedPhases?.get(phaseId) as? Map<*, *>
        ?: return MachineConfigTreeResult.Invalid(
          "$COMPACTION_KEY.$phaseKey.$phaseId",
          "the new phase override is missing from the submitted section.",
        )
    phases[phaseId] = requestedPhase.stringMap()
  }
  for ((phaseId, fields) in intent.phaseFields) {
    if (phaseId in intent.addedPhaseIds) continue
    mergePhaseFields(phases, requestedPhases, phaseId, fields)?.let { return it }
  }
  return null
}

private fun mergePhaseFields(
  phases: MutableMap<String, Any?>,
  requestedPhases: Map<*, *>?,
  phaseId: String,
  fields: Set<CompactionEditField>,
): MachineConfigTreeResult.Invalid? {
  val phaseKey = CompactionFieldKeys.PHASES
  val currentPhase =
    phases[phaseId] as? Map<*, *>
      ?: return MachineConfigTreeResult.Invalid(
        "$COMPACTION_KEY.$phaseKey.$phaseId",
        "does not exist in the current document. Reload before editing it.",
      )
  val requestedPhase =
    requestedPhases?.get(phaseId) as? Map<*, *>
      ?: return MachineConfigTreeResult.Invalid(
        "$COMPACTION_KEY.$phaseKey.$phaseId",
        "the edited phase is missing from the submitted section.",
      )
  val updatedPhase = currentPhase.stringMap()
  fields.forEach { field ->
    val key = field.key()
    if (!requestedPhase.containsKey(key)) {
      return MachineConfigTreeResult.Invalid(
        "$COMPACTION_KEY.$phaseKey.$phaseId.$key",
        "the edited value is missing from the submitted section.",
      )
    }
    updatedPhase[key] = requestedPhase[key]
  }
  phases[phaseId] = updatedPhase
  return null
}

private fun updateDirective(
  directives: MutableMap<String, Any?>,
  requestedDirectives: Map<*, *>?,
  key: String,
) {
  if (requestedDirectives?.containsKey(key) == true) {
    val requestedValue = requestedDirectives[key]
    directives[key] = (requestedValue as? Map<*, *>)?.stringMap() ?: requestedValue
  } else {
    directives.remove(key)
  }
}

private fun CompactionEditIntent.isEmpty(): Boolean =
  globalFields.isEmpty() && phaseFields.isEmpty() && addedPhaseIds.isEmpty() && removedPhaseIds.isEmpty()

private fun CompactionEditField.key(): String =
  when (this) {
    CompactionEditField.ENABLED -> CompactionFieldKeys.ENABLED
    CompactionEditField.WINDOW_TOKENS -> CompactionFieldKeys.WINDOW_TOKENS
    CompactionEditField.TRIGGER_PCT -> CompactionFieldKeys.TRIGGER_PCT
  }

private fun Map<*, *>.stringMap(): LinkedHashMap<String, Any?> =
  LinkedHashMap<String, Any?>().apply {
    this@stringMap.entries.forEach { (key, value) -> if (key is String) put(key, value) }
  }

private fun CompactionEditIntent.hasAddedPhases(): Boolean = addedPhaseIds.isNotEmpty()

private fun sectionUnchanged(
  original: ConfigDocumentTree,
  key: String,
  value: Any?,
  section: Map<String, Any?>,
): Boolean = if (original.contains(key)) value == section else section.isEmpty()
