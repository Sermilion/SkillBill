package dev.skillbill.desktop.feature.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbDialog
import dev.skillbill.desktop.core.designsystem.component.SbDialogText
import dev.skillbill.desktop.core.designsystem.component.SbField
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.designsystem.settings.*
import dev.skillbill.desktop.core.domain.model.*
import dev.skillbill.desktop.core.domain.repository.*
import dev.skillbill.desktop.core.ui.feedback.*
import dev.skillbill.desktop.core.ui.input.ShellEdit
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused
import kotlinx.coroutines.launch
import dev.skillbill.desktop.core.domain.model.Tier as ModelTier

private data class ConfigEdit(
  val key: String,
  val value: String,
  val default: String,
  val fields: Map<String, String>,
  val tier: ModelTier? = null,
  val override: Boolean = false,
  val save: suspend (Map<String, String>, ConfigFingerprint?) -> ConfigWriteOutcome,
)

@Composable
internal fun ConfigPage(
  page: String, repository: RuntimeConfigRepository, specs: SpecRepository,
  preferredRepo: String?, ledger: CommentWarningLedger, toasts: ToastQueue,
) {
  val scope = rememberCoroutineScope()
  val defaults = remember { repository.configDefaults() }
  var document by remember { mutableStateOf<ConfigDocument<*>?>(null) }
  var matrix by remember { mutableStateOf<ModelMatrixView?>(null) }
  var projects by remember { mutableStateOf(emptyList<SpecProject>()) }
  var project by remember { mutableStateOf<SpecProject?>(null) }
  var instructions by remember { mutableStateOf(emptyList<PhaseInstructionFile>()) }
  var detected by remember { mutableStateOf(emptyList<DetectedAgent>()) }
  var level by remember { mutableStateOf("anonymous") }
  var agent by remember { mutableStateOf(defaults.agents.firstOrNull { it.modelCapable }?.id.orEmpty()) }
  var problem by remember { mutableStateOf<String?>(null) }
  var editor by remember { mutableStateOf<ConfigEdit?>(null) }
  var draft by remember { mutableStateOf(emptyMap<String, String>()) }
  var changed by remember { mutableStateOf(false) }
  var warning by remember { mutableStateOf(false) }
  var resetDraft by remember { mutableStateOf(false) }
  var loaded by remember { mutableStateOf(0) }
  var readFailed by remember { mutableStateOf(false) }
  var saving by remember { mutableStateOf(false) }
  val rows = remember { arrayOf(emptyList<ConfigEdit>()) }
  suspend fun openFile(file: String) {
    if (specs.openFile(file) == OpenOutcome.NotOpened) toasts.show(ToastMessage(ToastTone.Problem, "Couldn't open ${file.substringAfterLast('/')}"))
  }
  suspend fun load() {
    problem = null
    readFailed = false
    if (page == "project") {
      when (val result = specs.projects()) {
        is Outcome.Success -> projects = result.value
        is Outcome.Failure -> { problem = readProblemText(result.error); readFailed = true; return }
      }
      if (project == null) project = projects.firstOrNull { it.repoRoot == preferredRepo } ?: projects.firstOrNull()
      val root = project?.repoRoot ?: return
      when (val result = repository.projectConfig(root)) {
        is ConfigRead.Document -> document = result.document
        is ConfigRead.Failed -> { problem = readProblemText(result.problem); readFailed = true; return }
      }
      instructions = repository.phaseInstructionFiles(root)
    } else {
      when (val result = repository.modelMatrix()) {
        is ModelMatrixRead.Success -> { matrix = result.value; document = result.value.document }
        is ModelMatrixRead.Failed -> { problem = readProblemText(result.problem); readFailed = true; return }
      }
      if (page == "telemetry") {
        detected = repository.detectedAgents()
        when (val result = repository.telemetryLevel()) {
          is TelemetryLevelRead.Known -> level = result.level.wireValue
          is TelemetryLevelRead.Unreadable -> problem = "${result.keyPath.orEmpty()}: ${result.reason}"
          is TelemetryLevelRead.Failed -> problem = readProblemText(result.problem)
        }
      }
    }
    loaded++
  }
  LaunchedEffect(page, project?.projectId) { load() }
  val fingerprint = (document as? ConfigDocument.Present<*>)?.fingerprint
  val path = when (val current = document) {
    is ConfigDocument.Present -> current.path
    is ConfigDocument.Absent -> current.path
    is ConfigDocument.Unreadable -> current.path
    null -> ""
  }
  val machine = (document as? ConfigDocument.Present<*>)?.values as? MachineConfig
  val projectConfig = (document as? ConfigDocument.Present<*>)?.values as? ProjectConfig
  val enabled = document != null && document !is ConfigDocument.Unreadable && !readFailed && !saving
  fun open(row: ConfigEdit) { editor = row; draft = row.fields; changed = false; warning = false; problem = null }
  suspend fun save(confirmed: Boolean = false) {
    val selected = editor ?: return
    val row = rows[0].firstOrNull { it.key == selected.key } ?: selected
    if (saving || readFailed || changed || document is ConfigDocument.Unreadable || document == null) return
    val current = document as? ConfigDocument.Present<*>
    if (page == "project" && current?.hasComments == true && !ledger.confirmed(path) && !confirmed) {
      warning = true
      return
    }
    if (confirmed) ledger.confirm(path)
    warning = false
    saving = true
    try {
      when (val result = row.save(draft, fingerprint)) {
        is ConfigWriteOutcome.Written -> {
          toasts.show(ToastMessage(ToastTone.Confirmation, "Saved to ${if (page == "project") "config.yaml" else "config.json"}. ${row.key}: ${draft.values.joinToString(" · ")}"))
          editor = null
          load()
        }
        is ConfigWriteOutcome.Invalid -> problem = "${result.keyPath}: ${result.reason}"
        ConfigWriteOutcome.ChangedOnDisk -> changed = true
        is ConfigWriteOutcome.Unreadable -> { problem = result.reason; document = ConfigDocument.Unreadable(path, null, result.reason) }
        is ConfigWriteOutcome.Failed -> problem = readProblemText(result.problem)
      }
    } catch (_: NoSuchElementException) {
      problem = "Choose one of the listed values."
    } catch (_: NumberFormatException) {
      problem = "Enter a whole number."
    } finally { saving = false }
  }
  fun machineRow(key: String, value: String, default: String, fields: Map<String, String> = mapOf(key to value), change: (Map<String, String>) -> MachineConfigChange) =
    ConfigEdit(key, value, default, fields) { input, expected -> repository.writeMachineConfig(listOf(change(input)), expected) }
  val title = when (page) { "models" -> "Models"; "compaction" -> "Compaction"; "project" -> "Project"; else -> "Telemetry and agents" }
  SettingsHeader(title, body = path)
  (document as? ConfigDocument.Unreadable)?.let { ConfigText("${it.keyPath.orEmpty()}: ${it.reason}") }
  problem?.let { ConfigText(it) }
  val built = mutableListOf<ConfigEdit>()
  when (page) {
    "models" -> {
      Segmented(defaults.agents.map { SegmentedOption(it.id, it.id, enabled = it.modelCapable, tooltip = if (it.modelCapable) null else "${it.id} cannot honor a model or effort directive") }, agent) { agent = it; editor = null }
      val current = machine?.executionMatrix ?: ExecutionMatrixConfig(emptyMap(), emptyMap(), emptyMap())
      val resolved = matrix?.agents?.get(agent)
      fun modelRow(id: String, tier: ModelTier, directive: ModelDirective?, phase: Boolean, overridden: Boolean) {
        val key = "execution_matrix.agents.$agent.$id"
        val fields = linkedMapOf("model" to directive?.model.orEmpty(), "effort" to directive?.effort.orEmpty())
        if (phase) fields["tier"] = tier.name.lowercase()
        built += ConfigEdit(key, directive?.let { "${it.model} ${it.effort.orEmpty()}" } ?: "not set", if (phase) "${defaults.phases.first { it.id == id }.defaultTier.name.lowercase()} tier" else "not set", fields, tier, overridden) { input, expected ->
          val model = input.getValue("model")
          if (model.isBlank()) ConfigWriteOutcome.Invalid("$key.model", "A model is required. To drop an override, use Reset to tier.")
          else {
            val selectedTier = if (phase) ModelTier.entries.firstOrNull { it.name.equals(input["tier"], true) } else tier
            if (selectedTier == null) ConfigWriteOutcome.Invalid("execution_matrix.phase_tiers.$id", "Use reasoning or implementation.")
            else {
              val d = ModelDirective(model, input["effort"]?.ifBlank { null })
              val updated = if (phase) current.copy(phaseTiers = current.phaseTiers + (id to selectedTier), agentPhaseOverrides = current.agentPhaseOverrides + (agent to (current.agentPhaseOverrides[agent].orEmpty() + (id to d))))
                else current.copy(agents = current.agents + (agent to (current.agents[agent].orEmpty() + (tier to d))))
              repository.writeMachineConfig(listOf(MachineConfigChange.SetExecutionMatrix(executionMatrixSection(updated, defaults))), expected)
            }
          }
        }
      }
      ModelTier.entries.forEach { modelRow(it.name.lowercase(), it, resolved?.tiers?.get(it), false, false) }
      resolved?.phases?.forEach { modelRow(it.phaseId, it.tier, it.directive, true, it.override) }
    }
    "compaction" -> {
      val value = machine?.compaction ?: defaults.compaction
      built += machineRow("compaction.enabled", value.enabled.toString(), "true") { input ->
        val bool = input.values.first().toBooleanStrictOrNull()
        if (bool == null) throw NumberFormatException()
        MachineConfigChange.SetCompaction(compactionSection(value.copy(enabled = bool), defaults.compaction))
      }
      built += machineRow("compaction.window_tokens", value.windowTokens.toString(), defaults.compaction.windowTokens.toString()) { MachineConfigChange.SetCompaction(compactionSection(value.copy(windowTokens = number(it.values.first())), defaults.compaction)) }
      built += machineRow("compaction.trigger_pct", value.triggerPct.toString(), defaults.compaction.triggerPct.toString()) { MachineConfigChange.SetCompaction(compactionSection(value.copy(triggerPct = number(it.values.first())), defaults.compaction)) }
      defaults.phases.forEach { phase ->
        val override = value.phases[phase.id]
        built += machineRow("compaction.phases.${phase.id}", override?.toString() ?: "inherits", "inherits", mapOf("window_tokens" to (override?.windowTokens ?: value.windowTokens).toString(), "trigger_pct" to (override?.triggerPct ?: value.triggerPct).toString())) {
          MachineConfigChange.SetCompaction(compactionSection(value.copy(phases = value.phases + (phase.id to PhaseCompaction(number(it.getValue("window_tokens")), number(it.getValue("trigger_pct"))))), defaults.compaction))
        }
      }
    }
    "telemetry" -> {
      built += ConfigEdit("telemetry.level", level, "anonymous", mapOf("level" to level)) { input, _ ->
        val selected = TelemetryLevel.entries.firstOrNull { it.wireValue == input["level"] }
        if (selected == null) ConfigWriteOutcome.Invalid("telemetry.level", "Use off, anonymous or full.") else repository.setTelemetryLevel(selected)
      }
      built += machineRow("telemetry.proxy_url", machine?.telemetryProxyUrl.orEmpty(), "default endpoint") { MachineConfigChange.SetTelemetryProxyUrl(it.values.first().ifBlank { null }) }
      built += machineRow("telemetry.batch_size", machine?.telemetryBatchSize?.toString().orEmpty(), defaults.telemetryBatchSize.toString()) { MachineConfigChange.SetTelemetryBatchSize(it.values.first().ifBlank { null }?.let(::number)) }
      defaults.agents.forEach { ConfigText("${it.id}: ${if (detected.any { found -> found.name == it.id }) "detected" else "not detected"}${if (!it.modelCapable) ". Cannot take a model or effort setting" else ""}") }
      listOf(path, defaults.installSelectionPath).forEach { file -> SbButton("Open $file", { scope.launch { openFile(file) } }) }
    }
    "project" -> {
      Row { projects.forEach { item -> SbButton(item.displayName, { project = item; editor = null; document = null }) } }
      project?.let { ConfigText("${it.displayName}: ${it.specsRoot}") }
      if (project?.repoRoot == null) ConfigText("No local checkout. This project has no checkout on this machine, so its config.yaml cannot be read.")
      else {
        SbButton("Open config.yaml", { scope.launch { openFile(path) } })
        fun projectRow(key: String, value: String, default: String, change: (String) -> ProjectConfigChange) {
          built += ConfigEdit(key, value, default, mapOf(key to value)) { input, expected -> repository.writeProjectConfig(project!!.repoRoot!!, listOf(change(input.values.first())), expected) }
        }
        projectRow("spec_type", projectConfig?.specSource?.name?.lowercase() ?: "local", "local") {
          ProjectConfigChange.SetSpecType(SpecSource.entries.first { source -> source.name.equals(it, true) })
        }
        projectRow("validation_gate.gradle_wrapper", projectConfig?.gradleWrapper.orEmpty(), "./gradlew") { ProjectConfigChange.SetGradleWrapper(it.ifBlank { null }) }
        val budget = projectConfig?.reviewBudget ?: defaults.reviewBudget
        reviewBudgetValues(budget).forEach { (key, value) ->
          projectRow("review_context_budget.$key", value.toString(), reviewBudgetValues(defaults.reviewBudget).getValue(key).toString()) { text ->
            ProjectConfigChange.SetReviewContextBudget(reviewBudgetValues(budget).toMutableMap().apply { put(key, text.replace(",", "").replace("_", "").trim().toLong()) }.filter { (field, number) -> number.toLong() != reviewBudgetValues(defaults.reviewBudget).getValue(field).toLong() })
          }
        }
        instructions.forEach { file ->
          ConfigText(".skill-bill/phases/${file.phaseId}.md: ${file.sizeBytes} of ${defaults.phaseInstructionCapBytes}${if (file.exceedsCap) ", over the 16 KiB cap" else ""}")
          SbButton("Open ${file.phaseId}", { scope.launch { openFile(file.path) } })
        }
      }
    }
  }
  rows[0] = built
  val renderEditor: @Composable (ConfigEdit) -> Unit = { row ->
    ShellEdit(onCancel = { editor = null }, onSave = { scope.launch { save() } })
    Column {
      draft.forEach { (field, value) ->
        val options = when (field) {
          "tier" -> listOf("reasoning", "implementation")
          "level" -> listOf("off", "anonymous", "full")
          "spec_type" -> listOf("local", "linear")
          "compaction.enabled" -> listOf("true", "false")
          else -> emptyList()
        }
        SbField(field, error = problem) {
          if (options.isEmpty()) SbInput(value, { draft = draft + (field to it); problem = null }, fieldModifier.gatesShortcutsWhileFocused(), mono = true, enabled = !saving && document !is ConfigDocument.Unreadable)
          else Row { options.forEach { option -> SbButton(option, {
            draft = draft + (field to option)
            if (field == "tier" && !row.override) {
              val directive = matrix?.agents?.get(agent)?.tiers?.get(ModelTier.entries.first { it.name.equals(option, true) })
              draft = draft + mapOf("model" to directive?.model.orEmpty(), "effort" to directive?.effort.orEmpty())
            }
            problem = null
          }, enabled = !saving && document !is ConfigDocument.Unreadable) } }
        }
      }
      if (changed) {
        ConfigText("${path.substringAfterLast('/')} changed on disk. Another program saved this file after you opened it. Nothing was saved.")
        SbButton("Reload", { scope.launch { load(); resetDraft = true; changed = false } })
        SbButton("Keep editing", { scope.launch { load(); changed = false } })
      }
      if (row.override || row.key.startsWith("compaction.phases.")) {
        SbButton(if (row.override) "Reset to tier" else "Remove override", { scope.launch {
          val change = if (row.override) {
            val current = machine?.executionMatrix ?: return@launch
            val id = row.key.substringAfterLast('.')
            MachineConfigChange.SetExecutionMatrix(executionMatrixSection(current.copy(agentPhaseOverrides = current.agentPhaseOverrides + (agent to (current.agentPhaseOverrides[agent].orEmpty() - id))), defaults))
          } else {
            val current = machine?.compaction ?: defaults.compaction
            MachineConfigChange.SetCompaction(compactionSection(current.copy(phases = current.phases - row.key.removePrefix("compaction.phases.")), defaults.compaction))
          }
          when (val result = repository.writeMachineConfig(listOf(change), fingerprint)) {
            is ConfigWriteOutcome.Written -> { editor = null; load() }
            ConfigWriteOutcome.ChangedOnDisk -> changed = true
            is ConfigWriteOutcome.Invalid -> problem = "${result.keyPath}: ${result.reason}"
            is ConfigWriteOutcome.Unreadable -> { problem = result.reason; document = ConfigDocument.Unreadable(path, null, result.reason) }
            is ConfigWriteOutcome.Failed -> problem = readProblemText(result.problem)
          }
        } }, enabled = !saving && !changed && document !is ConfigDocument.Unreadable)
      }
      SbButton("Save", { scope.launch { save() } }, enabled = !saving && !changed && !readFailed && document !is ConfigDocument.Unreadable && (draft["model"]?.isNotBlank() != false))
      SbButton("Cancel", { editor = null; warning = false })
    }
  }
  LaunchedEffect(loaded, resetDraft) {
    if (resetDraft) {
      rows[0].firstOrNull { it.key == editor?.key }?.let { editor = it; draft = it.fields }
      resetDraft = false
    }
  }
  SettingsGroup(title = title, rows = built.map { row -> { ConfigRow(row.key, row.value, { open(row) }, expanded = editor?.key == row.key, override = row.override, defaultValue = row.default, enabled = enabled, tier = row.tier?.let { if (it == ModelTier.REASONING) dev.skillbill.desktop.core.designsystem.settings.Tier.Reasoning else dev.skillbill.desktop.core.designsystem.settings.Tier.Implementation }, editor = { renderEditor(row) }) } })
  if (warning) {
    ShellLayer(onDismiss = { warning = false })
    SbDialog("Save without comments?", onDismissRequest = { warning = false }, actions = {
      SbButton("Cancel", { warning = false })
      SbButton("Save anyway", { scope.launch { save(true) } })
    }, body = {
      SbDialogText("${path.substringAfterLast('/')} has comments. Saving rewrites the file and removes them. Skill Bill asks once per file each time the app runs.")
    })
  }

}

private fun number(text: String): Int = text.replace(",", "").replace("_", "").trim().toInt()

@Composable
private fun ConfigText(text: String) {
  BasicText(text, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg))
}
