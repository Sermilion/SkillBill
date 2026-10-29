package dev.skillbill.desktop.feature.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbDialog
import dev.skillbill.desktop.core.designsystem.component.SbDialogText
import dev.skillbill.desktop.core.designsystem.component.SbField
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbToggle
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption
import dev.skillbill.desktop.core.designsystem.component.ToastData
import dev.skillbill.desktop.core.designsystem.settings.ConfigRow
import dev.skillbill.desktop.core.designsystem.settings.SettingRow
import dev.skillbill.desktop.core.designsystem.settings.SettingsGroup
import dev.skillbill.desktop.core.designsystem.settings.SettingsHeader
import dev.skillbill.desktop.core.designsystem.settings.Tier
import dev.skillbill.desktop.core.domain.model.AuthoredCompactionField
import dev.skillbill.desktop.core.domain.model.AuthoredCompactionFields
import dev.skillbill.desktop.core.domain.model.ConfigDocument
import dev.skillbill.desktop.core.domain.model.ConfigFingerprint
import dev.skillbill.desktop.core.domain.model.ConfigWriteOutcome
import dev.skillbill.desktop.core.domain.model.DetectedAgentsRead
import dev.skillbill.desktop.core.domain.model.ExecutionMatrixConfig
import dev.skillbill.desktop.core.domain.model.MachineConfig
import dev.skillbill.desktop.core.domain.model.MachineConfigAgentPhaseOverrideEdit
import dev.skillbill.desktop.core.domain.model.MachineConfigAgentTierDirectiveEdit
import dev.skillbill.desktop.core.domain.model.MachineConfigCompactionEditIntent
import dev.skillbill.desktop.core.domain.model.MachineConfigCompactionField
import dev.skillbill.desktop.core.domain.model.MachineConfigChange
import dev.skillbill.desktop.core.domain.model.MachineConfigExecutionMatrixEditIntent
import dev.skillbill.desktop.core.domain.model.MachineConfigPhaseTierEdit
import dev.skillbill.desktop.core.domain.model.ModelDirective
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.PhaseInstructionFilesRead
import dev.skillbill.desktop.core.domain.model.ProjectConfig
import dev.skillbill.desktop.core.domain.model.ProjectConfigChange
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecSource
import dev.skillbill.desktop.core.domain.model.TelemetryEffectiveLevel
import dev.skillbill.desktop.core.domain.model.TelemetryInstallIdSource
import dev.skillbill.desktop.core.domain.model.TelemetryLevel
import dev.skillbill.desktop.core.domain.model.TelemetryLevelRead
import dev.skillbill.desktop.core.domain.model.TelemetryLevelSnapshot
import dev.skillbill.desktop.core.domain.model.TelemetryOperationalAvailability
import dev.skillbill.desktop.core.domain.model.TelemetryPreviousLevel
import dev.skillbill.desktop.core.domain.model.Tier as ModelTier
import dev.skillbill.desktop.core.domain.repository.ConfigRead
import dev.skillbill.desktop.core.domain.repository.ModelMatrixRead
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.input.ShellEdit
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused
import dev.skillbill.desktop.core.ui.di.WindowScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path

internal data class ConfigEdit(
  val key: String,
  val value: String,
  val default: String,
  val fields: Map<String, String>,
  val group: String,
  val fieldPaths: Map<String, String> = fields.keys.associateWith { key -> if (key == "spec_type") "spec_type" else key },
  val modelRow: ModelRowKind? = null,
  val tier: ModelTier? = null,
  val override: Boolean = false,
  val tierChanged: Boolean = false,
  val save: suspend (Map<String, String>, Map<String, Map<String, String>>, ConfigDraftBasis) -> ConfigWriteOutcome,
  val remove: (suspend (ConfigDraftBasis) -> ConfigWriteOutcome)? = null,
  val toastId: String = key,
)

internal enum class ModelRowKind { TIER, PHASE }
internal data class CommentConfirmation(
  val path: String,
  val projectId: String?,
  val selectionRevision: Int,
  val rowKey: String,
  val fingerprint: ConfigFingerprint?,
  val draft: Map<String, String>,
)
internal data class PendingConflictResolution(
  val page: String,
  val readRevision: Int?,
  val rowKey: String,
  val keepDraft: Boolean,
  val draft: Map<String, String>,
  val modelProvenance: ModelRowDraftProvenance?,
  val basis: ConfigDraftBasis,
  val agent: String,
  val projectId: String?,
  val projectRoot: String?,
  val ticket: Any = Any(),
)

internal data class ConfigDraftBasis(
  val path: String,
  val fingerprint: ConfigFingerprint?,
  val candidatePaths: Set<String>,
  val projectStorePath: String?,
)

internal data class ModelRowDraftProvenance(
  val basis: ConfigDraftBasis,
  val editedFields: Set<String>,
  val openedWithExplicitOverride: Boolean,
)

internal class ConfigPageSession(page: String, agent: String) {
  val document = mutableStateOf<ConfigDocument<*>?>(null)
  val matrix = mutableStateOf<dev.skillbill.desktop.core.domain.model.ModelMatrixView?>(null)
  val lastReadableMachine = mutableStateOf<Pair<String, MachineConfig>?>(null)
  val projects = mutableStateOf(emptyList<SpecProject>())
  val project = mutableStateOf<SpecProject?>(null)
  val projectRepoRoot = mutableStateOf<String?>(null)
  val explicitProjectId = mutableStateOf<String?>(null)
  val projectSelectionRevision = mutableStateOf(0)
  val loadedProjectId = mutableStateOf<String?>(null)
  val loadedProjectRoot = mutableStateOf<String?>(null)
  val loadedPreferredRepo = mutableStateOf<String?>(null)
  val pendingPhaseIds = mutableStateOf(emptySet<String>())
  val compactionEditedFields = mutableStateOf(emptyMap<String, Set<String>>())
  val projectBudgetEditedFields = mutableStateOf(emptyMap<String, Set<String>>())
  val instructions = mutableStateOf<PhaseInstructionFilesRead?>(null)
  val detected = mutableStateOf<DetectedAgentsRead?>(null)
  val level = mutableStateOf<String?>(null)
  val telemetrySnapshot = mutableStateOf<TelemetryLevelSnapshot?>(null)
  val agent = mutableStateOf(agent)
  val editorKey = mutableStateOf<String?>(null)
  val editorPage = mutableStateOf(page)
  val openRows = mutableStateOf(emptySet<String>())
  val rowActivity = mutableStateOf(emptyList<String>())
  val draft = mutableStateOf(emptyMap<String, String>())
  val rowDrafts = mutableStateOf(emptyMap<String, Map<String, String>>())
  val modelDraftProvenance = mutableStateOf(emptyMap<String, ModelRowDraftProvenance>())
  val rowBases = mutableStateOf(emptyMap<String, ConfigDraftBasis>())
  val conflictedRows = mutableStateOf(emptySet<String>())
  val rowErrors = mutableStateOf(emptyMap<String, Map<String, String>>())
  val rowHints = mutableStateOf(emptyMap<String, String>())
  val participatingDrafts = mutableStateOf(emptyMap<String, Set<String>>())
  val errors = mutableStateOf(emptyMap<String, String>())
  val hint = mutableStateOf<String?>(null)
  val warning = mutableStateOf(false)
  val commentConfirmation = mutableStateOf<CommentConfirmation?>(null)
  val readProblem = mutableStateOf<String?>(null)
  val readFailed = mutableStateOf(false)
  val readInFlight = mutableStateOf(false)
  val saving = mutableStateOf(false)
  val reloadRevision = mutableStateOf(0)
  val readRevision = mutableStateOf(0)
  val readPage = mutableStateOf<String?>(null)
  val pendingConflictResolution = mutableStateOf<PendingConflictResolution?>(null)

  internal fun adoptRowBasis(
    rowKey: String,
    freshFields: Map<String, String>,
    freshBasis: ConfigDraftBasis,
    keepDraft: Boolean,
    freshInheritedFields: Map<String, String>? = null,
    freshExplicitOverride: Boolean = false,
    preservePendingPhase: Boolean = false,
  ): Map<String, String>? {
    if (rowKey !in openRows.value || rowKey !in conflictedRows.value) return null
    if (!keepDraft) {
      rowErrors.value = rowErrors.value - rowKey
      rowHints.value = rowHints.value - rowKey
      participatingDrafts.value = participatingDrafts.value.filterKeys { it != rowKey }
        .mapValues { (_, related) -> related - rowKey }.filterValues { it.isNotEmpty() }
      compactionEditedFields.value = compactionEditedFields.value - rowKey
      projectBudgetEditedFields.value = projectBudgetEditedFields.value - rowKey
      if (rowKey.startsWith("compaction.phases.") && !preservePendingPhase) {
        pendingPhaseIds.value = pendingPhaseIds.value - rowKey.removePrefix("compaction.phases.")
      }
      rowDrafts.value = rowDrafts.value + (rowKey to freshFields)
      if (freshFields[ConfigFileKeys.TIER] != null) {
        modelDraftProvenance.value = modelDraftProvenance.value + (rowKey to ModelRowDraftProvenance(freshBasis, emptySet(), freshExplicitOverride))
      } else {
        modelDraftProvenance.value = modelDraftProvenance.value - rowKey
      }
    } else if (freshFields[ConfigFileKeys.TIER] != null) {
      val currentDraft = rowDrafts.value[rowKey] ?: freshFields
      val currentProvenance = modelDraftProvenance.value[rowKey]
        ?: ModelRowDraftProvenance(rowBases.value[rowKey] ?: freshBasis, emptySet(), freshExplicitOverride)
      val source = when {
        currentProvenance.openedWithExplicitOverride -> null
        freshExplicitOverride -> freshFields
        else -> freshInheritedFields ?: freshFields
      }
      val retained = currentDraft.toMutableMap()
      source?.let { inherited ->
        listOf(ConfigFileKeys.MODEL, ConfigFileKeys.EFFORT).forEach { field ->
          if (field !in currentProvenance.editedFields) retained[field] = inherited[field].orEmpty()
        }
      }
      rowDrafts.value = rowDrafts.value + (rowKey to retained)
      modelDraftProvenance.value = modelDraftProvenance.value + (rowKey to currentProvenance.copy(
        basis = freshBasis,
        openedWithExplicitOverride = currentProvenance.openedWithExplicitOverride || freshExplicitOverride,
      ))
    } else if (rowKey.startsWith("compaction.")) {
      val currentDraft = rowDrafts.value[rowKey] ?: freshFields
      val edited = compactionEditedFields.value[rowKey].orEmpty()
      val retained = currentDraft.toMutableMap()
      freshFields.forEach { (field, value) ->
        if (field !in edited) retained[field] = value
      }
      rowDrafts.value = rowDrafts.value + (rowKey to retained)
    }
    rowBases.value = rowBases.value + (rowKey to freshBasis)
    conflictedRows.value = conflictedRows.value - rowKey
    return rowDrafts.value[rowKey]
  }
}

@Inject
@SingleIn(WindowScope::class)
@androidx.compose.runtime.Stable
class ConfigEditorSessions {
  private val sessions = mutableMapOf<String, ConfigPageSession>()
  private val machinePaths = mutableMapOf<String, String>()
  private val writeMutex = Mutex()
  internal val selectedAgent = mutableStateOf("claude")
  internal val selectedProjectKey = mutableStateOf<String?>(null)

  internal suspend fun <T> withWrite(block: suspend () -> T): T {
    writeMutex.lock()
    try {
      return block()
    } finally {
      writeMutex.unlock()
    }
  }

  internal fun hasWriteInFlight(): Boolean = writeMutex.isLocked || sessions.values.any { it.saving.value }

  internal fun hasConflictResolution(): Boolean = sessions.values.any { it.pendingConflictResolution.value != null }

  internal fun session(page: String, preferredRepo: String?): ConfigPageSession {
    val identity = when (page) {
      "models" -> "${selectedAgent.value}|${machinePaths["models|${selectedAgent.value}"].orEmpty()}"
      "project" -> selectedProjectKey.value ?: preferredRepo ?: "auto"
      else -> machinePaths["$page|"] ?: "unresolved"
    }
    val key = "$page|$identity"
    return sessions.getOrPut(key) { ConfigPageSession(page, selectedAgent.value) }
  }

  internal fun bindMachine(session: ConfigPageSession, page: String, agent: String, path: String) {
    val owner = if (page == "models") "$page|$agent" else "$page|"
    machinePaths[owner] = path
    val identity = if (page == "models") "$agent|$path" else path
    sessions["$page|$identity"] = session
  }

  internal fun selectProject(project: SpecProject) {
    val root = project.repoRoot
    val session = sessions.values.firstOrNull { it.project.value?.projectId == project.projectId && it.projectRepoRoot.value == root }
      ?: ConfigPageSession("project", selectedAgent.value)
    session.explicitProjectId.value = project.projectId
    bindProject(session, project, root, null)
  }

  internal fun bindProject(session: ConfigPageSession, project: SpecProject, root: String?, path: String?) {
    val identity = "${project.projectId}|${root.orEmpty()}|${path.orEmpty()}"
    if (session.explicitProjectId.value != null) selectedProjectKey.value = identity
    sessions["project|$identity"] = session
    if (session.project.value?.projectId == project.projectId && session.projectRepoRoot.value == root) return
    session.project.value = project
    session.projectRepoRoot.value = root
    session.projectSelectionRevision.value++
    session.loadedProjectId.value = null
    session.loadedProjectRoot.value = null
    session.document.value = null
    session.instructions.value = null
  }
}

@Composable
internal fun ConfigPage(
  page: String,
  repository: RuntimeConfigRepository,
  specs: SpecRepository,
  preferredRepo: String?,
  ledger: CommentWarningLedger,
  toasts: ToastQueue,
  session: ConfigPageSession,
  sessions: ConfigEditorSessions,
  windowScope: kotlinx.coroutines.CoroutineScope,
) {
  val scope = windowScope
  val defaults = remember { repository.configDefaults() }
  var document by session.document
  var matrix by session.matrix
  var lastReadableMachine by session.lastReadableMachine
  var projects by session.projects
  var project by session.project
  var projectRepoRoot by session.projectRepoRoot
  var explicitProjectId by session.explicitProjectId
  var projectSelectionRevision by session.projectSelectionRevision
  var loadedProjectId by session.loadedProjectId
  var loadedProjectRoot by session.loadedProjectRoot
  var loadedPreferredRepo by session.loadedPreferredRepo
  var pendingPhaseIds by session.pendingPhaseIds
  var instructions by session.instructions
  var detected by session.detected
  var level by session.level
  var telemetrySnapshot by session.telemetrySnapshot
  var agent by session.agent
  var editorKey by session.editorKey
  var editorPage by session.editorPage
  var openRows by session.openRows
  var rowActivity by session.rowActivity
  var draft by session.draft
  val rowDrafts = session.rowDrafts
  val compactionEditedFields = session.compactionEditedFields
  val projectBudgetEditedFields = session.projectBudgetEditedFields
  val modelDraftProvenance = session.modelDraftProvenance
  val rowBases = session.rowBases
  var conflictedRows by session.conflictedRows
  val rowErrors = session.rowErrors
  val rowHints = session.rowHints
  var participatingDrafts by session.participatingDrafts
  var errors by session.errors
  var hint by session.hint
  var warning by session.warning
  var commentConfirmation by session.commentConfirmation
  var readProblem by session.readProblem
  var readFailed by session.readFailed
  var readInFlight by session.readInFlight
  var saving by session.saving
  var reloadRevision by session.reloadRevision
  var readRevision by session.readRevision
  var readPage by session.readPage
  var pendingConflictResolution by session.pendingConflictResolution
  val rowIndex = remember { mutableStateOf(emptyMap<String, ConfigEdit>()) }
  var rowIndexRevision by remember { mutableStateOf(-1) }
  var rowIndexPage by remember { mutableStateOf("") }

  val changedOnDisk = conflictedRows.isNotEmpty()
  val resolutionBusy = pendingConflictResolution != null
  val anyResolutionBusy = sessions.hasConflictResolution()
  val resolutionActionsEnabled = !saving && !readInFlight && !anyResolutionBusy && !sessions.hasWriteInFlight()

  fun basisOf(value: ConfigDocument<*>?): ConfigDraftBasis? = when (value) {
    is ConfigDocument.Present<*> -> ConfigDraftBasis(value.path, value.fingerprint, value.candidatePaths.toSet(), value.projectStorePath)
    is ConfigDocument.Absent -> ConfigDraftBasis(value.path, null, value.candidatePaths.toSet(), value.projectStorePath)
    is ConfigDocument.Unreadable, null -> null
  }

  suspend fun openFile(file: String) {
    if (specs.openFile(file) == OpenOutcome.NotOpened) {
      toasts.show(ToastMessage(ToastTone.Problem, "Couldn't open ${file.substringAfterLast('/')}"))
    }
  }

  suspend fun openPhaseInstruction(file: String, projectId: String, repoRoot: String, expectedReadRevision: Int) {
    val currentProjectFiles = session.instructions.value
    val selectedProjectKey = sessions.selectedProjectKey.value
    if (
      session.project.value?.projectId != projectId || session.projectRepoRoot.value != repoRoot ||
      session.loadedProjectId.value != projectId || session.loadedProjectRoot.value != repoRoot ||
      session.readPage.value != "project" || session.readRevision.value != expectedReadRevision ||
      selectedProjectKey != null && !selectedProjectKey.startsWith("$projectId|$repoRoot|") ||
      currentProjectFiles?.files?.any { it.path == file } != true
    ) return
    if (specs.openFileInDefaultApp(file) == OpenOutcome.NotOpened) {
      toasts.show(ToastMessage(ToastTone.Problem, "Couldn't open ${file.substringAfterLast('/')}"))
    }
  }

  suspend fun performLoad(resolveConflict: Boolean, resolutionTicket: Any?): Boolean {
    val requestedPage = page
    val loadRevision = reloadRevision + 1
    reloadRevision = loadRevision
    if (page == "project") projectSelectionRevision++
    if (page == "project") instructions = null
    readProblem = null
    readFailed = true
    var nextDocument = document
    var nextMatrix = matrix
    var nextProjects = projects
    var nextInstructions = instructions
    var nextDetected = detected
    var nextLevel = level
    var nextTelemetrySnapshot = telemetrySnapshot
    fun ownsRead() = loadRevision == reloadRevision && (resolutionTicket == null || pendingConflictResolution?.ticket === resolutionTicket)
    if (page == "project") {
      when (val result = specs.projects()) {
        is Outcome.Success -> {
          if (!ownsRead()) return false
          nextProjects = result.value
        }
        is Outcome.Failure -> {
          if (!ownsRead()) return false
          readProblem = readProblemText(result.error)
          readFailed = true
          return false
        }
      }
      val currentProject = project
      val selection = when {
        changedOnDisk && resolveConflict -> currentProject?.let { current ->
          nextProjects.firstOrNull { it.projectId == current.projectId }?.let { it to projectRepoRoot }
        }
        explicitProjectId != null -> nextProjects.firstOrNull { it.projectId == explicitProjectId }?.let { it to it.repoRoot }
        preferredRepo != null -> when (val resolved = specs.projectFor(preferredRepo)) {
          is Outcome.Success -> {
            if (!ownsRead()) return false
            nextProjects.firstOrNull { it.projectId == resolved.value.projectId }?.let { it to (resolved.value.repoRoot ?: preferredRepo) }
          }
          is Outcome.Failure -> {
            if (!ownsRead()) return false
            readProblem = readProblemText(resolved.error)
            readFailed = true
            return false
          }
        }
        currentProject != null -> nextProjects.firstOrNull { it.projectId == currentProject.projectId }?.let { it to projectRepoRoot }
        else -> nextProjects.firstOrNull()?.let { it to it.repoRoot }
      }
      val selectedProject = selection?.first
      val root = selection?.second
      if (!ownsRead()) return false
      if (selection == null && (explicitProjectId != null || preferredRepo != null)) {
        projects = nextProjects
        readProblem = "The selected project could not be matched to the current project list."
        readFailed = true
        return false
      }
      val projectChanged = selectedProject?.projectId != project?.projectId || root != projectRepoRoot
      if (projectChanged && rowDrafts.value.isNotEmpty()) {
        projects = nextProjects
        readProblem = "The selected project changed while edits were open. Cancel them before loading another project."
        readFailed = true
        return false
      }
      if (projectChanged) {
        project = selectedProject
        projectRepoRoot = root
        projectSelectionRevision++
        loadedProjectId = null
        loadedProjectRoot = null
        nextDocument = null
        nextInstructions = null
        editorKey = null
        draft = emptyMap()
        openRows = emptySet()
        rowActivity = emptyList()
        rowDrafts.value = emptyMap()
        modelDraftProvenance.value = emptyMap()
        compactionEditedFields.value = emptyMap()
        projectBudgetEditedFields.value = emptyMap()
        rowErrors.value = emptyMap()
        rowHints.value = emptyMap()
        participatingDrafts = emptyMap()
        rowBases.value = emptyMap()
        conflictedRows = emptySet()
        warning = false
        nextTelemetrySnapshot = null
        commentConfirmation = null
        pendingConflictResolution = null
      }
      if (selectedProject == null || root == null) {
        projects = nextProjects
        document = null
        instructions = null
        loadedProjectId = selectedProject?.projectId
        loadedProjectRoot = null
        loadedPreferredRepo = preferredRepo
        if (explicitProjectId != null && selectedProject == null) readProblem = "The selected project is no longer available."
        readFailed = false
        readPage = requestedPage
        readRevision++
        return false
      }
      when (val result = repository.projectConfig(root)) {
        is ConfigRead.Document -> {
          if (!ownsRead()) return false
          nextDocument = result.document
        }
        is ConfigRead.Failed -> {
          if (!ownsRead()) return false
          readProblem = readProblemText(result.problem)
          readFailed = true
          return false
        }
      }
      sessions.bindProject(session, selectedProject, root, configDocumentPath(nextDocument))
      val phaseFiles = repository.phaseInstructionFiles(root)
      if (!ownsRead()) return false
      nextInstructions = phaseFiles
      projects = nextProjects
      loadedProjectId = selectedProject.projectId
      loadedProjectRoot = root
      if (!resolveConflict) loadedPreferredRepo = preferredRepo
      readFailed = nextDocument is ConfigDocument.Unreadable
    } else {
      if (page == "telemetry") {
        nextDetected = repository.detectedAgentsRead()
        if (!ownsRead()) return false
        detected = nextDetected
      }
      when (val result = repository.modelMatrix()) {
        is ModelMatrixRead.Success -> {
          if (!ownsRead()) return false
          nextMatrix = result.value
          nextDocument = result.value.document
        }
        is ModelMatrixRead.Failed -> {
          if (!ownsRead()) return false
          readProblem = readProblemText(result.problem)
          readFailed = true
          return false
        }
      }
      if (page != "telemetry") readFailed = nextDocument is ConfigDocument.Unreadable || nextDocument == null
      if (page == "telemetry") {
        when (val result = repository.telemetryLevel(nextMatrix?.document ?: return false)) {
          is TelemetryLevelRead.Known -> {
            if (!ownsRead()) return false
            nextLevel = result.level.wireValue
            nextTelemetrySnapshot = TelemetryLevelSnapshot(
              configuredLevel = result.level,
              effectiveLevel = TelemetryEffectiveLevel.Known(result.level),
              availability = TelemetryOperationalAvailability.Available(result.level),
              previousLevel = TelemetryPreviousLevel.Known(result.level),
              levelEnvironmentOverride = false,
              installIdSource = TelemetryInstallIdSource.CONFIG,
              configHasInstallId =
                ((nextDocument as? ConfigDocument.Present<*>)?.values as? MachineConfig)
                  ?.telemetryInstallId?.isNotBlank() == true,
              configPath = result.configPath,
            )
            readFailed = nextDocument is ConfigDocument.Unreadable || nextDocument == null
          }
          is TelemetryLevelRead.Snapshot -> {
            if (!ownsRead()) return false
            nextLevel = result.value.configuredLevel.wireValue
            nextTelemetrySnapshot = result.value
            readFailed = nextDocument is ConfigDocument.Unreadable || nextDocument == null
          }
          is TelemetryLevelRead.Unreadable -> {
            if (!ownsRead()) return false
            val configPath = when (val current = nextDocument) {
              is ConfigDocument.Present -> current.path
              is ConfigDocument.Absent -> current.path
              is ConfigDocument.Unreadable -> current.path
              null -> ""
            }
            nextLevel = null
            nextTelemetrySnapshot = null
            nextDocument = unreadableDocument(nextDocument, configPath, result.keyPath, result.reason)
            readFailed = true
          }
          is TelemetryLevelRead.Failed -> {
            if (!ownsRead()) return false
            readProblem = readProblemText(result.problem)
            readFailed = true
            return false
          }
        }
      }
    }
    if (!ownsRead()) return false
    val previousPath = configDocumentPath(document)
    val nextPath = configDocumentPath(nextDocument)
    val nextBasis = basisOf(nextDocument)
    val identityChanged = rowDrafts.value.isNotEmpty() && (
      previousPath != null && previousPath != nextPath || rowBases.value.values.any { it.path != nextPath }
    )
    val pathCanRebind = rowBases.value.values.all { configPathObservationAllowed(it, nextDocument) }
    if (identityChanged && !pathCanRebind) {
      readProblem = "The configuration file path changed while edits were open. Cancel those edits before continuing."
      readFailed = true
      return false
    }
    if (page != "project") nextPath?.let { sessions.bindMachine(session, page, agent, it) }
    if (nextBasis != null) {
      val newlyStale = rowDrafts.value.keys.filter { rowKey -> rowBases.value[rowKey] != nextBasis }.toSet()
      conflictedRows = conflictedRows + newlyStale
    }
    document = nextDocument
    matrix = nextMatrix
    if (page != "project") {
      when (val loaded = nextDocument) {
        is ConfigDocument.Present<*> -> (loaded.values as? MachineConfig)?.let { lastReadableMachine = loaded.path to it }
        is ConfigDocument.Absent -> lastReadableMachine = null
        is ConfigDocument.Unreadable -> Unit
        null -> Unit
      }
    }
    projects = nextProjects
    instructions = nextInstructions
    detected = nextDetected
    level = nextLevel
    telemetrySnapshot = nextTelemetrySnapshot
    readPage = requestedPage
    readRevision++
    if (conflictedRows.isNotEmpty()) editorPage = page
    return !readFailed && nextDocument !is ConfigDocument.Unreadable && nextDocument != null
  }

  suspend fun load(resolveConflict: Boolean = false, resolutionTicket: Any? = null): Boolean {
    val pending = pendingConflictResolution
    if (pending != null && pending.ticket !== resolutionTicket) return false
    if (resolutionTicket != null && (pending?.ticket !== resolutionTicket || saving)) return false
    if (readInFlight || commentConfirmation != null && !resolveConflict) return false
    readInFlight = true
    return try {
      performLoad(resolveConflict, resolutionTicket)
    } finally {
      readInFlight = false
    }
  }

  LaunchedEffect(page, preferredRepo, explicitProjectId, session, saving) {
    if (!saving && !resolutionBusy && !changedOnDisk && commentConfirmation == null) scope.launch { load() }
  }
  val fingerprint = (document as? ConfigDocument.Present<*>)?.fingerprint
  val path = when (val current = document) {
    is ConfigDocument.Present -> current.path
    is ConfigDocument.Absent -> current.path
    is ConfigDocument.Unreadable -> current.path
    null -> ""
  }
  val machine = (document as? ConfigDocument.Present<*>)?.values as? MachineConfig
  val unavailableDocument = document is ConfigDocument.Unreadable
  val retainedMachine = lastReadableMachine?.takeIf { it.first == path }?.second
  val projectConfig = (document as? ConfigDocument.Present<*>)?.values as? ProjectConfig
  val projectSelectionLoaded = page != "project" || loadedProjectId == project?.projectId && loadedProjectRoot == projectRepoRoot && (explicitProjectId != null || loadedPreferredRepo == preferredRepo)
  val enabled = document != null && document !is ConfigDocument.Unreadable && !readFailed && !saving && !anyResolutionBusy && !sessions.hasWriteInFlight() && projectSelectionLoaded && readPage == page && rowIndexRevision == readRevision && rowIndexPage == page

  fun selectActiveRow(rowKey: String) {
    editorPage = page
    editorKey = rowKey
    rowActivity = listOf(rowKey) + rowActivity.filterNot { it == rowKey }
    draft = rowDrafts.value[rowKey] ?: rowIndex.value[rowKey]?.fields.orEmpty()
    errors = rowErrors.value[rowKey].orEmpty()
    hint = rowHints.value[rowKey]
  }

  fun activate(rowKey: String): Boolean {
    if (rowKey !in openRows || rowIndexRevision != readRevision || rowIndexPage != page || rowIndex.value[rowKey] == null) return false
    selectActiveRow(rowKey)
    return true
  }

  fun selectRecentOpenRow() {
    val next = rowActivity.firstOrNull {
      it in openRows && rowIndexRevision == readRevision && rowIndexPage == page && rowIndex.value[it] != null
    }
    if (next == null) {
      editorKey = null
      editorPage = page
      draft = emptyMap()
      errors = emptyMap()
      hint = null
    } else {
      selectActiveRow(next)
    }
  }

  fun open(row: ConfigEdit) {
    if (!enabled || pendingConflictResolution != null || sessions.hasConflictResolution() || sessions.hasWriteInFlight()) return
    val retainedDraft = rowDrafts.value[row.key]
    openRows = openRows + row.key
    if (retainedDraft == null) {
      rowDrafts.value = rowDrafts.value + (row.key to row.fields)
      basisOf(document)?.let { basis ->
        rowBases.value = rowBases.value + (row.key to basis)
        if (row.modelRow == ModelRowKind.PHASE) {
          modelDraftProvenance.value = modelDraftProvenance.value + (row.key to ModelRowDraftProvenance(basis, emptySet(), row.override))
        }
      }
    } else if (row.modelRow == ModelRowKind.PHASE && row.key !in modelDraftProvenance.value) {
      rowBases.value[row.key]?.let { basis ->
        modelDraftProvenance.value = modelDraftProvenance.value + (row.key to ModelRowDraftProvenance(basis, emptySet(), row.override))
      }
    }
    activate(row.key)
    warning = false
    commentConfirmation = null
  }

  fun updateDraftField(rowKey: String, field: String, value: Map<String, String>) {
    if (pendingConflictResolution != null || sessions.hasConflictResolution() || sessions.hasWriteInFlight()) return
    activate(rowKey)
    rowDrafts.value = rowDrafts.value + (rowKey to value)
    rowErrors.value = rowErrors.value - rowKey
    rowHints.value = rowHints.value - rowKey
    val row = rowIndex.value[rowKey]
    if (page == "models" && row?.modelRow == ModelRowKind.PHASE && (field == ConfigFileKeys.MODEL || field == ConfigFileKeys.EFFORT)) {
      val basis = rowBases.value[rowKey] ?: basisOf(document)
      if (basis != null) {
        val provenance = modelDraftProvenance.value[rowKey]
          ?: ModelRowDraftProvenance(basis, emptySet(), row.override)
        modelDraftProvenance.value = modelDraftProvenance.value + (rowKey to provenance.copy(
          editedFields = provenance.editedFields + field,
        ))
      }
    }
    if (page == "compaction" && row?.key == rowKey && field in row.fields.keys) {
      compactionEditedFields.value = compactionEditedFields.value + (
        rowKey to (compactionEditedFields.value[rowKey].orEmpty() + field)
      )
    }
    if (page == "project" && rowKey.startsWith("${ConfigFileKeys.REVIEW_CONTEXT_BUDGET}.") && field == rowKey) {
      val fieldKey = rowKey.removePrefix("${ConfigFileKeys.REVIEW_CONTEXT_BUDGET}.")
      projectBudgetEditedFields.value = projectBudgetEditedFields.value + (
        rowKey to (projectBudgetEditedFields.value[rowKey].orEmpty() + fieldKey)
      )
    }
    if (editorKey == rowKey && editorPage == page) {
      draft = value
      errors = emptyMap()
      hint = null
    }
  }

  fun updateDraft(rowKey: String, value: Map<String, String>) = updateDraftField(rowKey, "", value)

  fun cancelEditor(targetKey: String? = editorKey) {
    if (pendingConflictResolution != null || sessions.hasConflictResolution() || sessions.hasWriteInFlight()) return
    val cancellingEditor = editorKey == targetKey || targetKey == null
    targetKey?.let { key ->
      rowDrafts.value = rowDrafts.value - key
      rowBases.value = rowBases.value - key
      modelDraftProvenance.value = modelDraftProvenance.value - key
      compactionEditedFields.value = compactionEditedFields.value - key
      projectBudgetEditedFields.value = projectBudgetEditedFields.value - key
      conflictedRows = conflictedRows - key
      rowErrors.value = rowErrors.value - key
      rowHints.value = rowHints.value - key
      openRows = openRows - key
      rowActivity = rowActivity.filterNot { it == key }
      participatingDrafts = participatingDrafts.filterKeys { it != key }.mapValues { (_, related) -> related - key }.filterValues { it.isNotEmpty() }
      if (key.startsWith("compaction.phases.")) pendingPhaseIds = pendingPhaseIds - key.removePrefix("compaction.phases.")
    }
    if (editorKey == targetKey) {
      selectRecentOpenRow()
    }
    if (cancellingEditor) {
      warning = false
      commentConfirmation = null
      errors = editorKey?.let { rowErrors.value[it] }.orEmpty()
      hint = editorKey?.let { rowHints.value[it] }
    }
  }

  fun applyInvalid(row: ConfigEdit, keyPath: String, reason: String) {
    val owners = invalidOwners(rowIndex.value.values.toList(), keyPath)
    if (owners.isEmpty()) {
      val rowHint = "$keyPath: $reason"
      rowHints.value = rowHints.value + (row.key to rowHint)
      if (editorKey == row.key && editorPage == page) hint = rowHint
    } else {
      val rowKeys = owners.map { it.key }.toSet()
      rowErrors.value = rowErrors.value + owners.associate { owner ->
        owner.key to owner.fieldsForInvalidKey(keyPath).associateWith { reason }
      }
      openRows = openRows + row.key + rowKeys
      val related = (participatingDrafts[row.key].orEmpty() + row.key + rowKeys)
        .flatMap { participatingDrafts[it].orEmpty() + it }
        .toSet()
      participatingDrafts = participatingDrafts + related.associateWith { related }
      owners.forEach { owner ->
        if (rowDrafts.value[owner.key] == null) {
          rowDrafts.value = rowDrafts.value + (owner.key to owner.fields)
          basisOf(document)?.let { basis ->
            rowBases.value = rowBases.value + (owner.key to basis)
          }
        }
        if (owner.modelRow == ModelRowKind.PHASE && owner.key !in modelDraftProvenance.value) {
          rowBases.value[owner.key]?.let { basis ->
            modelDraftProvenance.value = modelDraftProvenance.value + (owner.key to ModelRowDraftProvenance(basis, emptySet(), owner.override))
          }
        }
      }
      if (editorKey == row.key && editorPage == page) errors = rowErrors.value[row.key].orEmpty()
    }
  }

  suspend fun reload(keepDraft: Boolean, targetKey: String? = editorKey) {
    val retainedKey = targetKey
    val key = retainedKey ?: return
    val retained = rowDrafts.value[key] ?: return
    val basis = rowBases.value[key] ?: return
    if (saving || readInFlight || sessions.hasWriteInFlight() || sessions.hasConflictResolution() || key !in openRows || key !in conflictedRows) return
    if (!configPathObservationAllowed(basis, document) || rowIndexRevision != readRevision || rowIndexPage != page) return
    val request = PendingConflictResolution(
      page = page,
      readRevision = null,
      rowKey = key,
      keepDraft = keepDraft,
      draft = retained,
      modelProvenance = modelDraftProvenance.value[key],
      basis = basis,
      agent = agent,
      projectId = project?.projectId,
      projectRoot = projectRepoRoot,
    )
    pendingConflictResolution = request
    var transferredToRowIndex = false
    try {
      val loaded = load(resolveConflict = true, resolutionTicket = request.ticket)
      if (!loaded || pendingConflictResolution?.ticket !== request.ticket) {
        val unreadable = document as? ConfigDocument.Unreadable
        if (unreadable != null && readProblem == null) readProblem = "${unreadable.keyPath ?: unreadable.path}: ${unreadable.reason}"
        return
      }
      val currentBasis = basisOf(document)
      val ownsTarget =
        page == request.page && agent == request.agent && project?.projectId == request.projectId &&
          projectRepoRoot == request.projectRoot &&
          readPage == request.page && rowDrafts.value[key] == request.draft && rowBases.value[key] == request.basis &&
          modelDraftProvenance.value[key] == request.modelProvenance &&
          key in openRows && key in conflictedRows && currentBasis != null &&
          configPathTransitionAllowed(request.basis, currentBasis)
      if (!ownsTarget) {
        readProblem = "The edited row or its configuration file changed while the conflict was being resolved."
        return
      }
      pendingConflictResolution = request.copy(readRevision = readRevision)
      transferredToRowIndex = true
    } finally {
      if (!transferredToRowIndex && pendingConflictResolution?.ticket === request.ticket) pendingConflictResolution = null
    }
  }

  suspend fun save(targetKey: String? = editorKey, confirmed: Boolean = false) {
    val key = targetKey ?: return
    val row = rowIndex.value[key] ?: return
    val submitted = rowDrafts.value[key] ?: row.fields
    if (saving || sessions.hasConflictResolution() || sessions.hasWriteInFlight() || !enabled || key in conflictedRows || !projectSelectionLoaded || document !is ConfigDocument.Present && document !is ConfigDocument.Absent || rowIndexRevision != readRevision || rowIndex.value[key] !== row) return
    selectActiveRow(key)
    draft = submitted
    val attemptPath = path
    val attemptAgent = agent
    val attemptProjectId = project?.projectId
    val attemptProjectRoot = projectRepoRoot
    val projectRevision = projectSelectionRevision
    val attemptPage = page
    val attemptReadRevision = readRevision
    val attemptLoadRevision = reloadRevision
    val current = document as? ConfigDocument.Present<*>
    val relatedDraftKeys = participatingDrafts[key] ?: setOf(key)
    val authoredBudget = (current?.values as? ProjectConfig)?.authoredReviewContextBudget
    if (
      page == "project" && key.startsWith("${ConfigFileKeys.REVIEW_CONTEXT_BUDGET}.") &&
      authoredBudget?.present == true && authoredBudget.entries.isEmpty() &&
      relatedDraftKeys.none { projectBudgetEditedFields.value[it].orEmpty().isNotEmpty() }
    ) {
      cancelEditor(key)
      return
    }
    if (page == "project" && current?.hasComments == true && !ledger.confirmed(path) && !confirmed) {
      commentConfirmation = CommentConfirmation(path, project?.projectId, projectSelectionRevision, key, current.fingerprint, submitted)
      warning = true
      return
    }
    if (confirmed) {
      val pending = commentConfirmation ?: return
      if (pending.path != path || pending.projectId != project?.projectId || pending.selectionRevision != projectSelectionRevision || pending.rowKey != key || pending.fingerprint != fingerprint || pending.draft != submitted || !enabled || current?.hasComments != true) {
        warning = false
        commentConfirmation = null
        return
      }
      ledger.confirm(path)
    }
    warning = false
    saving = true
    errors = emptyMap()
    rowErrors.value = rowErrors.value - relatedDraftKeys
    rowHints.value = rowHints.value - relatedDraftKeys
    hint = null
    try {
      val included = relatedDraftKeys.associateWith { rowKey ->
        rowDrafts.value[rowKey] ?: rowIndex.value[rowKey]?.fields.orEmpty()
      }
      val acceptedBasis = basisOf(document)
      val staleParticipants = included.keys.filter { rowKey -> rowBases.value[rowKey] != acceptedBasis }.toSet()
      val staleModelDrafts = included.keys.filter { rowKey ->
        modelDraftProvenance.value[rowKey]?.basis?.let { it != acceptedBasis } == true
      }.toSet()
      if (acceptedBasis == null || staleParticipants.isNotEmpty() || staleModelDrafts.isNotEmpty() || included.keys.any { it in conflictedRows }) {
        conflictedRows = conflictedRows + if (acceptedBasis == null) included.keys else staleParticipants
        conflictedRows = conflictedRows + staleModelDrafts
        return
      }
      val expectedFingerprint = acceptedBasis.fingerprint
      val result = sessions.withWrite { row.save(submitted, included, acceptedBasis) }
      val latestPath = when (val latest = document) {
        is ConfigDocument.Present -> latest.path
        is ConfigDocument.Absent -> latest.path
        is ConfigDocument.Unreadable -> latest.path
        null -> ""
      }
      val attemptIsCurrent = latestPath == attemptPath && readRevision == attemptReadRevision && reloadRevision == attemptLoadRevision && rowIndexRevision == attemptReadRevision && rowIndexPage == attemptPage && if (page == "project") {
        projectSelectionRevision == projectRevision && project?.projectId == attemptProjectId && projectRepoRoot == attemptProjectRoot
      } else page != "models" || agent == attemptAgent
      if (!attemptIsCurrent) return
      when (result) {
        is ConfigWriteOutcome.Written -> {
          if (page == "project" && (projectRevision != projectSelectionRevision || !projectSelectionLoaded)) return
          val fileName = if (page == "project") "config.yaml" else "config.json"
          val body = if (row.modelRow != null) {
            val model = submitted[ConfigFileKeys.MODEL].orEmpty()
            val effort = submitted[ConfigFileKeys.EFFORT].orEmpty()
            "${row.toastId}: $model${if (effort.isBlank()) "" else " · $effort"}"
          } else "${row.toastId}: ${submitted.values.joinToString(" · ")}"
          toasts.show(ToastData(title = "Saved to $fileName", body = body))
          if (result.fingerprint != expectedFingerprint) {
            conflictedRows = conflictedRows + (rowDrafts.value.keys - included.keys)
          }
          openRows = openRows - included.keys
          rowDrafts.value = rowDrafts.value - included.keys
          compactionEditedFields.value = compactionEditedFields.value - included.keys
          projectBudgetEditedFields.value = projectBudgetEditedFields.value - included.keys
          modelDraftProvenance.value = modelDraftProvenance.value - included.keys
          rowBases.value = rowBases.value - included.keys
          rowErrors.value = rowErrors.value - included.keys
          rowHints.value = rowHints.value - included.keys
          rowActivity = rowActivity.filterNot { it in included.keys }
          if (editorKey != null && editorKey in included.keys) selectRecentOpenRow()
          participatingDrafts = participatingDrafts.filterKeys { it !in included.keys }
          commentConfirmation = null
          pendingPhaseIds = pendingPhaseIds - included.keys.filter { it.startsWith("compaction.phases.") }.map { it.removePrefix("compaction.phases.") }.toSet()
          load()
        }
        is ConfigWriteOutcome.Invalid -> {
          if (page == "project" && (projectRevision != projectSelectionRevision || !projectSelectionLoaded)) return
          applyInvalid(row, result.keyPath, result.reason)
        }
        ConfigWriteOutcome.ChangedOnDisk -> if (page != "project" || projectRevision == projectSelectionRevision && projectSelectionLoaded) conflictedRows = conflictedRows + included.keys
        is ConfigWriteOutcome.Unreadable -> if (page != "project" || projectRevision == projectSelectionRevision && projectSelectionLoaded) document = unreadableDocument(document, result.path ?: path, result.keyPath, result.reason)
        is ConfigWriteOutcome.Failed -> if (page != "project" || projectRevision == projectSelectionRevision && projectSelectionLoaded) {
          val rowHint = readProblemText(result.problem)
          rowHints.value = rowHints.value + (key to rowHint)
          if (editorKey == key) hint = rowHint
        }
      }
    } finally {
      if (confirmed) {
        warning = false
        commentConfirmation = null
      }
      saving = false
    }
  }

  suspend fun remove(row: ConfigEdit) {
    val remove = row.remove ?: return
    if (saving || sessions.hasConflictResolution() || sessions.hasWriteInFlight() || !enabled || row.key in conflictedRows || !projectSelectionLoaded || document !is ConfigDocument.Present && document !is ConfigDocument.Absent || document is ConfigDocument.Unreadable || rowIndexRevision != readRevision || rowIndex.value[row.key] !== row) return
    val acceptedBasis = basisOf(document)
    if (acceptedBasis == null || rowBases.value[row.key] != acceptedBasis) {
      conflictedRows = conflictedRows + row.key
      return
    }
    activate(row.key)
    val attemptPath = path
    val attemptAgent = agent
    val attemptProjectId = project?.projectId
    val attemptProjectRoot = projectRepoRoot
    val attemptPage = page
    val attemptReadRevision = readRevision
    val attemptLoadRevision = reloadRevision
    val expectedFingerprint = acceptedBasis.fingerprint
    saving = true
    val projectRevision = projectSelectionRevision
    errors = emptyMap()
    hint = null
    rowHints.value = rowHints.value - row.key
    try {
      val result = sessions.withWrite { remove(acceptedBasis) }
      val latestPath = when (val latest = document) {
        is ConfigDocument.Present -> latest.path
        is ConfigDocument.Absent -> latest.path
        is ConfigDocument.Unreadable -> latest.path
        null -> ""
      }
      val attemptIsCurrent = latestPath == attemptPath && readRevision == attemptReadRevision && reloadRevision == attemptLoadRevision && rowIndexRevision == attemptReadRevision && rowIndexPage == attemptPage && if (page == "project") {
        projectSelectionRevision == projectRevision && project?.projectId == attemptProjectId && projectRepoRoot == attemptProjectRoot
      } else page != "models" || agent == attemptAgent
      if (!attemptIsCurrent) return
      when (result) {
        is ConfigWriteOutcome.Written -> {
          if (page == "project" && (projectRevision != projectSelectionRevision || !projectSelectionLoaded)) return
          if (result.fingerprint != expectedFingerprint) {
            conflictedRows = conflictedRows + (rowDrafts.value.keys - row.key)
          }
          if (row.key.startsWith("execution_matrix.agents.")) {
            val tier = row.tier?.name?.lowercase().orEmpty()
            toasts.show(ToastMessage(ToastTone.Confirmation, "${row.toastId} uses the $tier tier again"))
          }
          openRows = openRows - row.key
          rowDrafts.value = rowDrafts.value - row.key
          compactionEditedFields.value = compactionEditedFields.value - row.key
          projectBudgetEditedFields.value = projectBudgetEditedFields.value - row.key
          modelDraftProvenance.value = modelDraftProvenance.value - row.key
          rowBases.value = rowBases.value - row.key
          conflictedRows = conflictedRows - row.key
          rowErrors.value = rowErrors.value - row.key
          rowHints.value = rowHints.value - row.key
          rowActivity = rowActivity.filterNot { it == row.key }
          participatingDrafts = participatingDrafts.filterKeys { it != row.key }
            .mapValues { (_, related) -> related - row.key }.filterValues { it.isNotEmpty() }
          if (editorKey == row.key) selectRecentOpenRow()
          load()
        }
        is ConfigWriteOutcome.Invalid -> if (page != "project" || projectRevision == projectSelectionRevision && projectSelectionLoaded) applyInvalid(row, result.keyPath, result.reason)
        ConfigWriteOutcome.ChangedOnDisk -> if (page != "project" || projectRevision == projectSelectionRevision && projectSelectionLoaded) conflictedRows = conflictedRows + row.key
        is ConfigWriteOutcome.Unreadable -> if (page != "project" || projectRevision == projectSelectionRevision && projectSelectionLoaded) document = unreadableDocument(document, result.path ?: path, result.keyPath, result.reason)
        is ConfigWriteOutcome.Failed -> if (page != "project" || projectRevision == projectSelectionRevision && projectSelectionLoaded) {
          val rowHint = readProblemText(result.problem)
          rowHints.value = rowHints.value + (row.key to rowHint)
          if (editorKey == row.key) hint = rowHint
        }
      }
    } finally {
      saving = false
    }
  }

  fun machineRow(
    key: String,
    value: String,
    default: String,
    group: String,
    fields: Map<String, String> = mapOf(key to value),
    remove: (suspend (ConfigDraftBasis) -> ConfigWriteOutcome)? = null,
    sectionChange: ((Map<String, Map<String, String>>) -> MachineConfigChange)? = null,
    change: (Map<String, String>) -> MachineConfigChange,
  ) = ConfigEdit(key, if (unavailableDocument) "unavailable" else value, default, fields, group, fieldPaths = machineFieldPaths(key, fields), save = save@{ input, included, basis ->
    val edit = try {
      sectionChange?.invoke(included + (key to input)) ?: change(input)
    } catch (_: IllegalArgumentException) {
      return@save ConfigWriteOutcome.Invalid(key, "Enter a whole number that fits this field.")
    }
    repository.writeMachineConfigFromSnapshot(basis.path, listOf(edit), basis.fingerprint)
  }, remove = remove)

  val built = mutableListOf<ConfigEdit>()
  val title = when (page) { "models" -> "Models"; "compaction" -> "Compaction"; "project" -> "Project"; else -> "Telemetry and agents" }
  val projectStorePath = (document as? ConfigDocument.Present<*>)?.projectStorePath
    ?: (document as? ConfigDocument.Absent)?.projectStorePath
    ?: (document as? ConfigDocument.Unreadable)?.projectStorePath
  SettingsHeader(title, body = if (page == "project") projectStorePath ?: path else path)
  (document as? ConfigDocument.Unreadable)?.let {
    Callout(FeedbackTone.Error, title = it.keyPath ?: it.path, body = it.reason)
  }
  readProblem?.let { Callout(FeedbackTone.Error, body = it) }

  when (page) {
    "models" -> {
      Segmented(defaults.agents.map { item ->
        SegmentedOption(item.id, item.id, enabled = modelAgentSelectable(item) && !changedOnDisk && !saving && !anyResolutionBusy && commentConfirmation == null, tooltip = if (item.modelCapable) null else "${item.id} cannot honor a model or effort directive")
      }, agent) { selected ->
        if (!changedOnDisk && !saving && !sessions.hasConflictResolution() && !sessions.hasWriteInFlight() && commentConfirmation == null && defaults.agents.any { it.id == selected && modelAgentSelectable(it) }) {
          if (selected != agent) {
            sessions.selectedAgent.value = selected
          }
        }
      }
      defaults.agents.filterNot { it.modelCapable }.forEach { item ->
        Hint("${item.id} cannot honor a model or effort directive")
      }
      val current = machine?.executionMatrix ?: ExecutionMatrixConfig(emptyMap(), emptyMap(), emptyMap())
      val resolved = matrix?.agents?.get(agent)
      fun editModelDraft(row: ConfigEdit, field: String, input: Map<String, String>) {
        val previousTier = (rowDrafts.value[row.key] ?: row.fields)[ConfigFileKeys.TIER]
        val selectedTier = ModelTier.entries.firstOrNull { it.name.equals(input[ConfigFileKeys.TIER], true) }
        var next = input
        if (field == ConfigFileKeys.TIER && row.modelRow == ModelRowKind.PHASE && selectedTier != null && selectedTier.name.lowercase() != previousTier) {
          val provenance = modelDraftProvenance.value[row.key]
          if (provenance?.openedWithExplicitOverride != true && !row.override) {
            val inherited = resolved?.tiers?.get(selectedTier)
            if (ConfigFileKeys.MODEL !in provenance?.editedFields.orEmpty()) {
              next = next + (ConfigFileKeys.MODEL to inherited?.model.orEmpty())
            }
            if (ConfigFileKeys.EFFORT !in provenance?.editedFields.orEmpty()) {
              next = next + (ConfigFileKeys.EFFORT to inherited?.effort.orEmpty())
            }
          }
        }
        updateDraftField(row.key, field, next)
      }
      fun modelRow(id: String, tier: ModelTier, directive: ModelDirective?, phase: Boolean, overridden: Boolean, tierChanged: Boolean = false, unavailable: Boolean = false) {
        val key = if (phase) id else "${tier.name.lowercase()}"
        val configKey = if (phase) "execution_matrix.agents.$agent.$id" else "execution_matrix.agents.$agent.${tier.name.lowercase()}"
        val fields = linkedMapOf(ConfigFileKeys.MODEL to directive?.model.orEmpty(), ConfigFileKeys.EFFORT to directive?.effort.orEmpty())
        if (phase) fields[ConfigFileKeys.TIER] = tier.name.lowercase()
        val value = if (unavailable) "unavailable" else directive?.let { it.model + (it.effort?.let { effort -> " · $effort" } ?: "") } ?: "not set"
        built += ConfigEdit(configKey, value, if (phase) "${defaults.phases.firstOrNull { it.id == id }?.defaultTier?.name?.lowercase()} tier" else "not set", fields, if (phase) defaults.phases.firstOrNull { it.id == id }?.group?.name?.replace('_', ' ') ?: "Other" else "Tiers", fieldPaths = fields.keys.associateWith { field ->
          when (field) {
            ConfigFileKeys.TIER -> "execution_matrix.phase_tiers.$id"
            else -> "execution_matrix.agents.$agent.$id.$field"
          }
        }, modelRow = if (phase) ModelRowKind.PHASE else ModelRowKind.TIER, tier = if (phase && !unavailable) tier else null, override = overridden, tierChanged = tierChanged, save = { input, included, basis ->
          val model = input[ConfigFileKeys.MODEL].orEmpty()
          if (model.isBlank()) ConfigWriteOutcome.Invalid("$configKey.model", "A model is required.")
          else {
            val selectedTier = if (phase) ModelTier.entries.firstOrNull { it.name.equals(input[ConfigFileKeys.TIER], true) } else tier
            if (selectedTier == null) ConfigWriteOutcome.Invalid("execution_matrix.phase_tiers.$id", "Use reasoning or implementation.")
            else {
              val drafts = included + (configKey to input)
              val updated = executionMatrixWithDrafts(current, drafts, agent)
              val editIntent = executionMatrixEditIntent(current, updated, agent, drafts.keys)
              repository.writeMachineConfigFromSnapshot(
                basis.path,
                listOf(
                  MachineConfigChange.SetExecutionMatrix(
                    executionMatrixSection(updated, defaults, current),
                    editIntent,
                  ),
                ),
                basis.fingerprint,
              )
            }
          }
        }, remove = if (phase && overridden) ({ basis ->
          val updated = current.copy(
            agentPhaseOverrides = current.agentPhaseOverrides + (agent to (current.agentPhaseOverrides[agent].orEmpty() - id)),
          )
          repository.writeMachineConfigFromSnapshot(
            basis.path,
            listOf(
              MachineConfigChange.SetExecutionMatrix(
                executionMatrixSection(updated, defaults, current),
                MachineConfigExecutionMatrixEditIntent(
                  agentPhaseOverrides = listOf(MachineConfigAgentPhaseOverrideEdit(agent, id)),
                ),
              ),
            ),
            basis.fingerprint,
          )
        }) else null, toastId = key)
      }
      ModelTier.entries.forEach { tier -> modelRow(tier.name.lowercase(), tier, resolved?.tiers?.get(tier), false, false, unavailable = unavailableDocument) }
      if (unavailableDocument) {
        defaults.phases.forEach { phase -> modelRow(phase.id, phase.defaultTier, null, true, false, unavailable = true) }
      } else {
        resolved?.phases?.forEach { phase -> modelRow(phase.phaseId, phase.tier, phase.directive, true, phase.override, phase.tierChanged) }
      }
      SettingsGroup(
        title = "Reasoning and implementation tiers",
        rows = built.filter { it.group == "Tiers" }.map { row ->
          configRow(row, openRows, ::open, enabled) {
            renderEditor(
              path.substringAfterLast('/'), row, rowDrafts.value[row.key] ?: row.fields,
              rowErrors.value[row.key].orEmpty(), if (editorKey == row.key) hint else null,
              row.key in conflictedRows, saving, enabled, resolutionActionsEnabled,
              { updateDraft(row.key, it) }, { scope.launch { save(row.key) } },
              { cancelEditor(row.key) }, { scope.launch { reload(false, row.key) } },
              { scope.launch { reload(true, row.key) } }, { scope.launch { remove(row) } },
              onActivate = { activate(row.key) },
            )
          }
        },
      )
      defaults.phases.map { it.group }.distinct().forEach { kind ->
        val groupRows = built.filter { it.group == kind.name.replace('_', ' ') }
        if (groupRows.isNotEmpty()) {
          SettingsGroup(title = groupTitle(kind.name), rows = groupRows.map { row ->
            configRow(row, openRows, ::open, enabled) {
              renderEditor(
                path.substringAfterLast('/'), row, rowDrafts.value[row.key] ?: row.fields,
                rowErrors.value[row.key].orEmpty(), if (editorKey == row.key) hint else null,
                row.key in conflictedRows, saving, enabled, resolutionActionsEnabled,
                { updateDraft(row.key, it) }, { scope.launch { save(row.key) } },
                { cancelEditor(row.key) }, { scope.launch { reload(false, row.key) } },
                { scope.launch { reload(true, row.key) } }, { scope.launch { remove(row) } },
                onActivate = { activate(row.key) },
                onFieldChange = { field, input -> editModelDraft(row, field, input) },
              )
            }
          })
        }
      }
    }
    "compaction" -> {
      val value = machine?.compaction ?: if (unavailableDocument) retainedMachine?.compaction ?: defaults.compaction else defaults.compaction
      val authored = machine?.compactionFields ?: AuthoredCompactionFields.EMPTY
      val openPhaseIds = (openRows + rowDrafts.value.keys)
        .filter { it.startsWith("compaction.phases.") }
        .map { it.removePrefix("compaction.phases.") }
        .toSet()
      val retainedPhaseIds = pendingPhaseIds + openPhaseIds
      val freshCompactionValuesAvailable = !unavailableDocument && !readFailed
      val compactionSave: (Map<String, Map<String, String>>) -> MachineConfigChange = { edits ->
        val editedFields = compactionEditedFields.value.filterKeys { it in edits }
        val authoredPhaseIds = when (val phases = authored.phases) {
          is AuthoredCompactionField.Value -> phases.value.keys
          AuthoredCompactionField.Missing, AuthoredCompactionField.Null -> emptySet()
        }
        val submittedPhaseIds = edits.keys.mapNotNull { rowKey ->
          rowKey.takeIf { it.startsWith("compaction.phases.") }?.removePrefix("compaction.phases.")
        }
        val newPhaseIds = submittedPhaseIds.filterTo(mutableSetOf()) { phaseId ->
          phaseId in pendingPhaseIds || phaseId !in authoredPhaseIds
        }
        MachineConfigChange.SetCompaction(
          compactionSection(
            authored = authored,
            edits = edits,
            editedFields = editedFields,
            newPhaseIds = newPhaseIds,
          ),
          compactionEditIntent(editedFields, newPhaseIds),
        )
      }
      built += machineRow("compaction.enabled", value.enabled.toString(), "on", "Compaction", mapOf(ConfigFileKeys.ENABLED to value.enabled.toString()), sectionChange = compactionSave) { input -> compactionSave(mapOf("compaction.enabled" to input)) }
      built += machineRow("compaction.window_tokens", value.windowTokens.toString(), defaults.compaction.windowTokens.toString(), "Compaction", mapOf(ConfigFileKeys.WINDOW_TOKENS to value.windowTokens.toString()), sectionChange = compactionSave) { input -> compactionSave(mapOf("compaction.window_tokens" to input)) }
      built += machineRow("compaction.trigger_pct", value.triggerPct.toString(), defaults.compaction.triggerPct.toString(), "Compaction", mapOf(ConfigFileKeys.TRIGGER_PCT to value.triggerPct.toString()), sectionChange = compactionSave) { input -> compactionSave(mapOf("compaction.trigger_pct" to input)) }
      value.phases.forEach { (id, override) ->
        val key = "compaction.phases.$id"
        built += machineRow("compaction.phases.$id", "${override.windowTokens} tokens · ${override.triggerPct}%", "inherits", "Per-phase overrides", mapOf(ConfigFileKeys.WINDOW_TOKENS to override.windowTokens.toString(), ConfigFileKeys.TRIGGER_PCT to override.triggerPct.toString()), change = { input ->
          compactionSave(mapOf(key to input))
        }, remove = { basis ->
          repository.writeMachineConfigFromSnapshot(
            basis.path,
            listOf(
              MachineConfigChange.SetCompaction(
                compactionSection(authored, removedPhaseIds = setOf(id)),
                MachineConfigCompactionEditIntent(removedPhaseIds = setOf(id)),
              ),
            ),
            basis.fingerprint,
          )
        }, sectionChange = compactionSave)
      }
      retainedPhaseIds.filterNot { it in value.phases }.forEach { id ->
        val key = "compaction.phases.$id"
        val inheritedFields = mapOf(
          ConfigFileKeys.WINDOW_TOKENS to value.windowTokens.toString(),
          ConfigFileKeys.TRIGGER_PCT to value.triggerPct.toString(),
        )
        val fields = if (freshCompactionValuesAvailable) inheritedFields else rowDrafts.value[key] ?: emptyMap()
        val inheritedValue = "not saved · inherits ${value.windowTokens} tokens · ${value.triggerPct}%"
        built += machineRow(key, if (freshCompactionValuesAvailable) inheritedValue else "unavailable", "inherits the global settings", "Per-phase overrides", fields, sectionChange = compactionSave) { input ->
          compactionSave(mapOf(key to input))
        }
      }
      SettingsGroup(title = "Compaction", rows = built.filter { it.group == "Compaction" }.map { row -> configRow(row, openRows, ::open, enabled) { renderEditor(path.substringAfterLast('/'), row, rowDrafts.value[row.key] ?: row.fields, rowErrors.value[row.key].orEmpty(), if (editorKey == row.key) hint else null, row.key in conflictedRows, saving, enabled, resolutionActionsEnabled, { updateDraft(row.key, it) }, { scope.launch { save(row.key) } }, { cancelEditor(row.key) }, { scope.launch { reload(false, row.key) } }, { scope.launch { reload(true, row.key) } }, { scope.launch { remove(row) } }, onFieldChange = { field, values -> updateDraftField(row.key, field, values) }) } })
      if (!unavailableDocument && value.phases.isEmpty()) Hint("compaction.phases is empty, so every phase uses the setting above.")
      SettingsGroup(title = "Per-phase overrides", rows = built.filter { it.group == "Per-phase overrides" }.map { row -> configRow(row, openRows, ::open, enabled) { renderEditor(path.substringAfterLast('/'), row, rowDrafts.value[row.key] ?: row.fields, rowErrors.value[row.key].orEmpty(), if (editorKey == row.key) hint else null, row.key in conflictedRows, saving, enabled, resolutionActionsEnabled, { updateDraft(row.key, it) }, { scope.launch { save(row.key) } }, { cancelEditor(row.key) }, { scope.launch { reload(false, row.key) } }, { scope.launch { reload(true, row.key) } }, { scope.launch { remove(row) } }, onFieldChange = { field, values -> updateDraftField(row.key, field, values) }) } })
      val missing = if (!freshCompactionValuesAvailable) emptyList() else defaults.phases.filterNot { it.id in value.phases || it.id in retainedPhaseIds }
      if (missing.isNotEmpty()) {
        SettingsGroup(title = "Add phase override", rows = missing.map { phase ->
          { SettingRow(label = phase.id, description = "Add an override for this phase", control = {
            SbButton("Add override", {
              if (changedOnDisk || saving || sessions.hasConflictResolution() || sessions.hasWriteInFlight()) return@SbButton
              val key = "compaction.phases.${phase.id}"
              if (key in rowDrafts.value) return@SbButton
              pendingPhaseIds = pendingPhaseIds + phase.id
              editorKey = key
              editorPage = page
              openRows = openRows + editorKey!!
              rowActivity = listOf(editorKey!!) + rowActivity.filterNot { it == editorKey }
              draft = mapOf(ConfigFileKeys.WINDOW_TOKENS to value.windowTokens.toString(), ConfigFileKeys.TRIGGER_PCT to value.triggerPct.toString())
              rowDrafts.value = rowDrafts.value + (editorKey!! to draft)
              basisOf(document)?.let { rowBases.value = rowBases.value + (editorKey!! to it) }
              errors = emptyMap()
              hint = null
            }, enabled = enabled && !changedOnDisk && !saving && !anyResolutionBusy && !sessions.hasWriteInFlight())
          }) }
        })
      }
    }
    "telemetry" -> {
      val currentLevel = if (unavailableDocument) "unavailable" else level ?: "unavailable"
      val previousLevel = telemetrySnapshot?.previousLevel
        ?: TelemetryLevel.entries.firstOrNull { it.wireValue == level }
          ?.let(TelemetryPreviousLevel::Known)
        ?: TelemetryPreviousLevel.Unavailable
      built += ConfigEdit("telemetry.level", currentLevel, "anonymous", mapOf(ConfigFileKeys.LEVEL to (level ?: "")), "Telemetry", fieldPaths = mapOf(ConfigFileKeys.LEVEL to "telemetry.level"), save = { input, _, basis ->
        val selected = TelemetryLevel.entries.firstOrNull { it.wireValue == input[ConfigFileKeys.LEVEL] }
        if (selected == null) ConfigWriteOutcome.Invalid("telemetry.level", "Choose off, anonymous or full.") else repository.setTelemetryLevelFromSnapshot(selected, basis.path, basis.fingerprint, previousLevel)
      })
      built += machineRow("telemetry.proxy_url", machine?.telemetryProxyUrl.orEmpty(), "default endpoint", "Telemetry", mapOf(ConfigFileKeys.PROXY_URL to machine?.telemetryProxyUrl.orEmpty())) { input -> MachineConfigChange.SetTelemetryProxyUrl(input.getValue(ConfigFileKeys.PROXY_URL).ifBlank { null }) }
      built += machineRow("telemetry.batch_size", machine?.telemetryBatchSize?.toString() ?: defaults.telemetryBatchSize.toString(), defaults.telemetryBatchSize.toString(), "Telemetry", mapOf(ConfigFileKeys.BATCH_SIZE to (machine?.telemetryBatchSize?.toString() ?: defaults.telemetryBatchSize.toString()))) { input -> MachineConfigChange.SetTelemetryBatchSize(input.getValue(ConfigFileKeys.BATCH_SIZE).ifBlank { null }?.let(::number)) }
      telemetrySnapshot?.let { snapshot ->
        if (snapshot.levelEnvironmentOverride) {
          when (val effective = snapshot.effectiveLevel) {
            is TelemetryEffectiveLevel.Known -> Hint(
              "The environment overrides the configured level. Runtime level: ${effective.level.wireValue}.",
            )
            is TelemetryEffectiveLevel.Unavailable -> Unit
          }
        }
        when (val operational = snapshot.availability) {
          is TelemetryOperationalAvailability.Unavailable ->
            Callout(FeedbackTone.Warn, title = "Telemetry is unavailable", body = operational.reason)
          is TelemetryOperationalAvailability.Available -> if (
            !snapshot.levelEnvironmentOverride && snapshot.configuredLevel == TelemetryLevel.ANONYMOUS &&
            operational.level == TelemetryLevel.OFF && document is ConfigDocument.Absent
          ) Hint("The file is absent, so telemetry stays off until you save a level.")
        }
        if (snapshot.installIdSource == TelemetryInstallIdSource.ENVIRONMENT && !snapshot.configHasInstallId) {
          Hint("The environment supplies the install ID. This config file does not store one.")
        }
      }
      SettingsGroup(title = "Telemetry", rows = built.map { row -> configRow(row, openRows, ::open, enabled) { renderEditor(path.substringAfterLast('/'), row, rowDrafts.value[row.key] ?: row.fields, rowErrors.value[row.key].orEmpty(), if (editorKey == row.key) hint else null, row.key in conflictedRows, saving, enabled, resolutionActionsEnabled, { updateDraft(row.key, it) }, { scope.launch { save(row.key) } }, { cancelEditor(row.key) }, { scope.launch { reload(false, row.key) } }, { scope.launch { reload(true, row.key) } }, { scope.launch { remove(row) } }) } })
      SettingsGroup(title = "Agents", foot = "Detected agents are read-only.", rows = defaults.agents.map { item ->
        {
          val status = when (val result = detected) {
            is DetectedAgentsRead.Success -> if (result.agents.any { it.name.equals(item.id, true) }) "detected" else "not detected"
            is DetectedAgentsRead.Failed -> "detection unavailable · ${readProblemText(result.problem)}"
            null -> "detection unavailable"
          }
          BasicText("${item.id} · $status${if (item.modelCapable) "" else " · Cannot take a model or effort setting"}", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg))
        }
      })
      SettingsGroup(title = "Files", rows = listOf(path, defaults.installSelectionPath).map { file ->
        { SettingRow(label = file, control = { SbButton("Open", { scope.launch { openFile(file) } }) }) }
      })
    }
    "project" -> {
      if (projects.isNotEmpty()) {
        SettingsGroup(title = "Project", rows = projects.map { item ->
          { SettingRow(label = item.displayName, description = if (project?.projectId == item.projectId) "Selected" else null, control = {
            SbButton(if (project?.projectId == item.projectId) "Selected" else "Choose", {
              if (changedOnDisk || saving || sessions.hasConflictResolution() || sessions.hasWriteInFlight()) return@SbButton
              sessions.selectProject(item)
            }, enabled = !changedOnDisk && !saving && !anyResolutionBusy && !sessions.hasWriteInFlight() && commentConfirmation == null)
          }) }
        })
      }
      project?.let { BasicText("${it.displayName} · ${it.specsRoot}", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg)) }
      if (projectRepoRoot == null) {
        Callout(FeedbackTone.Info, title = "No local checkout", body = "This project has no checkout on this machine, so its config.yaml cannot be read.")
      } else {
        val storePath = projectStorePath ?: path
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          BasicText(storePath, style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgMuted))
          SbButton("Open config.yaml", { scope.launch { openFile(storePath) } }, enabled = storePath.isNotBlank() && projectSelectionLoaded)
        }
        val budget = projectConfig?.reviewBudget ?: defaults.reviewBudget
        val currentBudget = reviewBudgetValues(budget)
        val defaultBudget = reviewBudgetValues(defaults.reviewBudget)
        val budgetPrefix = "${ConfigFileKeys.REVIEW_CONTEXT_BUDGET}."
        fun projectRow(key: String, value: String, default: String, group: String, change: (String) -> ProjectConfigChange) {
          val selectedRoot = projectRepoRoot ?: return
          built += ConfigEdit(
            key,
            if (unavailableDocument) "unavailable" else value,
            default,
            mapOf(key to value),
            group,
            save = { input, _, basis ->
              repository.writeProjectConfigFromSnapshot(
                selectedRoot,
                basis.path,
                listOf(change(input.values.first())),
                basis.fingerprint,
              )
            },
          )
        }
        fun projectBudgetRow(key: String, value: Number) {
          val selectedRoot = projectRepoRoot ?: return
          val rowKey = "$budgetPrefix$key"
          built += ConfigEdit(
            rowKey,
            if (unavailableDocument) "unavailable" else value.toString(),
            defaultBudget.getValue(key).toString(),
            mapOf(rowKey to value.toString()),
            "Review context budget",
            save = save@{ input, included, basis ->
              val edits = included + (rowKey to input)
              val section = linkedMapOf<String, Any?>().apply {
                projectConfig?.authoredReviewContextBudget?.entries?.forEach { entry -> put(entry.key, entry.value) }
              }
              try {
                for ((editedRow, fields) in edits) {
                  if (!editedRow.startsWith(budgetPrefix)) continue
                  val field = editedRow.removePrefix(budgetPrefix)
                  if (field !in projectBudgetEditedFields.value[editedRow].orEmpty()) continue
                  val text = fields[editedRow] ?: fields.values.firstOrNull() ?: continue
                  val original = currentBudget[field] ?: continue
                  section[field] = if (original is Int) number(text) else longNumber(text)
                }
              } catch (_: IllegalArgumentException) {
                return@save ConfigWriteOutcome.Invalid(rowKey, "Enter a whole number that fits this field.")
              }
              repository.writeProjectConfigFromSnapshot(
                selectedRoot,
                basis.path,
                listOf(ProjectConfigChange.SetReviewContextBudget(section)),
                basis.fingerprint,
              )
            },
          )
        }
        projectRow(ConfigFileKeys.SPEC_TYPE, projectConfig?.specSource?.name?.lowercase() ?: "local", "local", "Project config") {
          ProjectConfigChange.SetSpecType(SpecSource.entries.first { source -> source.name.equals(it, true) })
        }
        projectRow(ConfigFileKeys.GRADLE_WRAPPER, projectConfig?.gradleWrapper.orEmpty(), "./gradlew", "Project config") {
          ProjectConfigChange.SetGradleWrapper(it.ifBlank { null })
        }
        currentBudget.forEach { (key, value) -> projectBudgetRow(key, value) }
        SettingsGroup(title = "Project config", rows = built.filter { it.group == "Project config" }.map { row -> configRow(row, openRows, ::open, enabled) { renderEditor(path.substringAfterLast('/'), row, rowDrafts.value[row.key] ?: row.fields, rowErrors.value[row.key].orEmpty(), if (editorKey == row.key) hint else null, row.key in conflictedRows, saving, enabled, resolutionActionsEnabled, { updateDraft(row.key, it) }, { scope.launch { save(row.key) } }, { cancelEditor(row.key) }, { scope.launch { reload(false, row.key) } }, { scope.launch { reload(true, row.key) } }, { scope.launch { remove(row) } }) } })
        SettingsGroup(title = "Review context budget", foot = "Byte limits must be positive.", rows = built.filter { it.group == "Review context budget" }.map { row -> configRow(row, openRows, ::open, enabled) { renderEditor(path.substringAfterLast('/'), row, rowDrafts.value[row.key] ?: row.fields, rowErrors.value[row.key].orEmpty(), if (editorKey == row.key) hint else null, row.key in conflictedRows, saving, enabled, resolutionActionsEnabled, { updateDraft(row.key, it) }, { scope.launch { save(row.key) } }, { cancelEditor(row.key) }, { scope.launch { reload(false, row.key) } }, { scope.launch { reload(true, row.key) } }, { scope.launch { remove(row) } }, onFieldChange = { field, values -> updateDraftField(row.key, field, values) }) } })
        val instructionFiles = instructions?.files.orEmpty()
        SettingsGroup(title = "Phase instructions", rows = instructionFiles.map { file ->
          val expectedProjectId = project?.projectId
          val expectedRepoRoot = projectRepoRoot
          val expectedReadRevision = readRevision
          val sizeDescription = file.sizeBytes?.let { size ->
            "$size of ${defaults.phaseInstructionCapBytes} bytes${if (file.exceedsCap == true) " · over the 16 KiB cap" else ""}"
          } ?: "Size unavailable · ${defaults.phaseInstructionCapBytes / 1024} KiB cap"
          {
            SettingRow(
              label = ".skill-bill/phases/${file.phaseId}.md",
              description = sizeDescription,
              control = {
                SbButton(
                  "Open",
                  {
                    scope.launch {
                      val selectedProjectId = expectedProjectId ?: return@launch
                      val selectedRepoRoot = expectedRepoRoot ?: return@launch
                      openPhaseInstruction(file.path, selectedProjectId, selectedRepoRoot, expectedReadRevision)
                    }
                  },
                  enabled = projectSelectionLoaded && !readInFlight && instructions != null,
                )
              },
            )
          }
        })
        (instructions as? PhaseInstructionFilesRead.Incomplete)?.let { result ->
          val problem = result.problems.joinToString("\n") { readProblemText(it) }
          Callout(
            FeedbackTone.Warn,
            title = "Phase instruction list is incomplete",
            body = problem.ifBlank { "Some phase instruction files could not be inspected. The page will retry on its next load." },
          )
        }
        Hint("Phase instruction files are committed with the repository, have a 16 KiB cap, and are read at launch.")
      }
    }
  }

  val renderedRows = built.associateBy { it.key }
  rowIndex.value = renderedRows
  rowIndexRevision = readRevision
  rowIndexPage = page
  fun activeShortcutRowKey(): String? = session.editorKey.value?.takeIf { key ->
    session.editorPage.value == page && key in session.openRows.value &&
      rowIndexRevision == readRevision && rowIndexPage == page && rowIndex.value[key] != null &&
      (page != "models" || session.agent.value == agent && sessions.selectedAgent.value == agent)
  }
  val currentRow = activeShortcutRowKey()?.let { renderedRows[it] }
  LaunchedEffect(page, readRevision, rowIndexRevision, rowIndexPage, editorKey, pendingConflictResolution) {
    val pending = pendingConflictResolution
    if (pending != null) {
      if (pending.readRevision == null || pending.page != page || pending.readRevision != readRevision || rowIndexRevision != readRevision || rowIndexPage != page) return@LaunchedEffect
      if (session.pendingConflictResolution.value?.ticket !== pending.ticket) return@LaunchedEffect
      val freshBasis = basisOf(document)
      val ownsTarget =
        pending.page == page && pending.agent == agent && pending.projectId == project?.projectId &&
          pending.projectRoot == projectRepoRoot &&
          readPage == pending.page && freshBasis != null && configPathTransitionAllowed(pending.basis, freshBasis) && rowDrafts.value[pending.rowKey] == pending.draft &&
          modelDraftProvenance.value[pending.rowKey] == pending.modelProvenance &&
          rowBases.value[pending.rowKey] == pending.basis && pending.rowKey in openRows && pending.rowKey in conflictedRows
      if (!ownsTarget) {
        readProblem = "The edited row or its configuration file changed while the conflict was being resolved."
        pendingConflictResolution = null
        return@LaunchedEffect
      }
      val freshRow = renderedRows[pending.rowKey]
      if (freshRow == null) {
        readProblem = "The edited row is not present in the freshly read configuration."
        pendingConflictResolution = null
        return@LaunchedEffect
      }
      val compactionPhaseId = pending.rowKey.removePrefix("compaction.phases.")
        .takeIf { pending.rowKey.startsWith("compaction.phases.") }
      val authoredCompactionPhases = ((document as? ConfigDocument.Present<*>)?.values as? MachineConfig)
        ?.compactionFields?.phases
      val compactionPhaseExists = compactionPhaseId?.let { phaseId ->
        (authoredCompactionPhases as? AuthoredCompactionField.Value)?.value?.containsKey(phaseId) == true
      } == true
      val preservePendingPhase = compactionPhaseId != null && compactionPhaseId in pendingPhaseIds && !compactionPhaseExists
      val inheritedFields = if (freshRow.modelRow == ModelRowKind.PHASE && !freshRow.override) {
        val selectedTier = ModelTier.entries.firstOrNull { tier ->
          tier.name.equals(pending.draft[ConfigFileKeys.TIER] ?: freshRow.fields[ConfigFileKeys.TIER], true)
        }
        val inherited = selectedTier?.let { matrix?.agents?.get(pending.agent)?.tiers?.get(it) }
        mapOf(
          ConfigFileKeys.MODEL to inherited?.model.orEmpty(),
          ConfigFileKeys.EFFORT to inherited?.effort.orEmpty(),
        )
      } else {
        null
      }
      val rebasedDraft = session.adoptRowBasis(
        pending.rowKey,
        freshRow.fields,
        freshBasis!!,
        pending.keepDraft,
        inheritedFields,
        freshRow.override,
        preservePendingPhase,
      )
      if (rebasedDraft == null) {
        readProblem = "The edited row changed while the conflict was being resolved."
        pendingConflictResolution = null
        return@LaunchedEffect
      }
      if (compactionPhaseExists && compactionPhaseId != null) pendingPhaseIds = pendingPhaseIds - compactionPhaseId
      if (editorKey == pending.rowKey && editorPage == page) {
        draft = rebasedDraft
        if (!pending.keepDraft) {
          errors = emptyMap()
          hint = rowHints.value[pending.rowKey]
        }
      }
      pendingConflictResolution = null
    } else if (!changedOnDisk && currentRow != null && draft.isEmpty()) {
      draft = currentRow.fields
    }
  }
  conflictedRows.filter { it in openRows && it !in renderedRows }.forEach { missingRowKey ->
    Callout(FeedbackTone.Warn, title = "${path.substringAfterLast('/')} changed on disk", body = readProblem ?: "$missingRowKey is not present in the freshly read configuration.", actions = {
      SbButton("Reload", { scope.launch { reload(false, missingRowKey) } }, enabled = resolutionActionsEnabled)
      SbButton("Keep editing", { scope.launch { reload(true, missingRowKey) } }, enabled = resolutionActionsEnabled)
    })
  }
  if (currentRow != null) {
    ShellEdit(
      onCancel = { activeShortcutRowKey()?.let(::cancelEditor) },
      onSave = {
        val targetKey = activeShortcutRowKey()
        if (targetKey != null) scope.launch { save(targetKey) }
      },
    )
    if (warning) {
      ShellLayer(onDismiss = { warning = false; commentConfirmation = null })
      SbDialog("Save without comments?", onDismissRequest = { warning = false; commentConfirmation = null }, actions = {
        SbButton("Cancel", { warning = false; commentConfirmation = null }, enabled = enabled)
        SbButton("Save anyway", { scope.launch { save(commentConfirmation?.rowKey, true) } }, enabled = enabled)
      }, body = {
        SbDialogText("${path.substringAfterLast('/')} has comments. Saving rewrites the file and removes them. Skill Bill asks once per file each time the app runs.")
      })
    }
  }
}

@Composable
private fun configRow(row: ConfigEdit, openRows: Set<String>, open: (ConfigEdit) -> Unit, enabled: Boolean, editor: @Composable ColumnScope.() -> Unit): @Composable () -> Unit = {
  ConfigRow(
    key = row.key,
    value = row.value,
    onToggle = { open(row) },
    expanded = row.key in openRows,
    override = row.override,
    defaultValue = row.default,
    enabled = enabled,
    tier = row.tier?.let { if (it == ModelTier.REASONING) Tier.Reasoning else Tier.Implementation },
    tags = {
      if (row.tierChanged) BasicText("tier changed", style = SkillBillTheme.type.t2xs.copy(color = SkillBillTheme.colors.fgMuted))
    },
    editor = editor,
  )
}

@Composable
private fun renderEditor(
  fileName: String,
  row: ConfigEdit,
  draft: Map<String, String>,
  errors: Map<String, String>,
  hint: String?,
  changed: Boolean,
  saving: Boolean,
  editorEnabled: Boolean,
  resolutionActionsEnabled: Boolean,
  onDraft: (Map<String, String>) -> Unit,
  onSave: () -> Unit,
  onCancel: () -> Unit,
  onReload: () -> Unit,
  onKeepEditing: () -> Unit,
  onRemove: () -> Unit,
  onActivate: (() -> Unit)? = null,
  onFieldChange: ((String, Map<String, String>) -> Unit)? = null,
) {
  fun change(field: String, values: Map<String, String>) {
    val handler = onFieldChange
    if (handler == null) onDraft(values) else handler(field, values)
  }

  Column(
    modifier = onActivate?.let { activate ->
      Modifier.focusGroup().onFocusChanged { if (it.hasFocus) activate() }
    } ?: Modifier,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    draft.forEach { (field, value) ->
      val options = when (field) {
        "tier" -> listOf("reasoning", "implementation")
        "level" -> listOf("off", "anonymous", "full")
        "spec_type" -> listOf("local", "linear")
        "enabled" -> listOf("true", "false")
        else -> emptyList()
      }
      SbField(
        label = when (field) { "model" -> "Model"; "effort" -> "Effort"; "tier" -> "Tier"; "proxy_url" -> "Proxy URL"; "batch_size" -> "Batch size"; "window_tokens" -> "Window tokens"; "trigger_pct" -> "Trigger percent"; "enabled" -> "Enabled"; "level" -> "Level"; "spec_type" -> "Spec source"; else -> field.substringAfterLast('.') },
        help = when (field) { "model" -> "Passed to the agent as typed."; "effort" -> "Optional."; "tier" -> "Applies to every agent."; else -> null },
        error = errors[field],
      ) {
        if (field == "enabled") {
          SbToggle(value.toBoolean(), { change(field, draft + (field to it.toString())) }, "Enabled", enabled = editorEnabled && !saving && !changed)
        } else if (options.isEmpty()) {
          SbInput(
            value,
            { change(field, draft + (field to it)) },
            Modifier.gatesShortcutsWhileFocused(),
            placeholder = if (field == "effort") "unset" else if (field == "proxy_url") "default" else null,
            mono = true,
            isError = errors[field] != null,
            enabled = editorEnabled && !saving && !changed,
          )
        } else {
          Segmented(options.map { SegmentedOption(it, it, enabled = editorEnabled && !saving && !changed) }, value) { change(field, draft + (field to it)) }
        }
      }
    }
    hint?.let { Hint(it, FeedbackTone.Error) }
    if (changed) Callout(FeedbackTone.Warn, title = "$fileName changed on disk", body = "Another program saved this file after you opened it. Nothing was saved.", actions = {
      SbButton("Reload", onReload, enabled = resolutionActionsEnabled)
      SbButton("Keep editing", onKeepEditing, enabled = resolutionActionsEnabled)
    })
    if (row.remove != null) SbButton(if (row.modelRow == ModelRowKind.PHASE) "Reset to tier" else "Remove override", onRemove, enabled = editorEnabled && !saving && !changed)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      SbButton("Save", onSave, enabled = editorEnabled && !saving && !changed && (draft[ConfigFileKeys.MODEL]?.isNotBlank() != false))
      SbButton("Cancel", onCancel, enabled = !saving && resolutionActionsEnabled)
    }
  }
}

private fun number(text: String): Int = text.replace(",", "").replace("_", "").trim().toIntOrNull() ?: throw IllegalArgumentException("Enter a whole number.")

private fun longNumber(text: String): Long = text.replace(",", "").replace("_", "").trim().toLongOrNull() ?: throw IllegalArgumentException("Enter a whole number.")

private fun compactionEditIntent(
  editedFields: Map<String, Set<String>>,
  addedPhaseIds: Set<String>,
): MachineConfigCompactionEditIntent {
  val globalFields = editedFields.entries
    .filterNot { it.key.startsWith("compaction.phases.") }
    .flatMap { it.value }
    .mapNotNull(::compactionField)
    .toSet()
  val phaseFields = editedFields.entries.mapNotNull { (rowKey, fields) ->
    if (!rowKey.startsWith("compaction.phases.")) return@mapNotNull null
    val phaseId = rowKey.removePrefix("compaction.phases.")
    val selectedFields = fields.mapNotNull(::compactionField).toSet()
    phaseId.takeIf { selectedFields.isNotEmpty() }?.let { it to selectedFields }
  }.toMap()
  return MachineConfigCompactionEditIntent(
    globalFields = globalFields,
    phaseFields = phaseFields,
    addedPhaseIds = addedPhaseIds,
  )
}

private fun compactionField(key: String): MachineConfigCompactionField? = when (key) {
  ConfigFileKeys.ENABLED -> MachineConfigCompactionField.ENABLED
  ConfigFileKeys.WINDOW_TOKENS -> MachineConfigCompactionField.WINDOW_TOKENS
  ConfigFileKeys.TRIGGER_PCT -> MachineConfigCompactionField.TRIGGER_PCT
  else -> null
}

private fun executionMatrixEditIntent(
  base: ExecutionMatrixConfig,
  updated: ExecutionMatrixConfig,
  agent: String,
  selectedRows: Set<String>,
): MachineConfigExecutionMatrixEditIntent {
  val tierDirectives = mutableListOf<MachineConfigAgentTierDirectiveEdit>()
  val phaseTiers = mutableListOf<MachineConfigPhaseTierEdit>()
  val phaseOverrides = mutableListOf<MachineConfigAgentPhaseOverrideEdit>()
  val prefix = "execution_matrix.agents.$agent."
  selectedRows.filter { it.startsWith(prefix) }.forEach { rowKey ->
    val id = rowKey.removePrefix(prefix)
    val tier = ModelTier.entries.firstOrNull { it.name.equals(id, true) }
    if (tier != null) {
      if (base.agents[agent]?.get(tier) != updated.agents[agent]?.get(tier)) {
        tierDirectives += MachineConfigAgentTierDirectiveEdit(agent, tier)
      }
    } else {
      if (base.phaseTiers[id] != updated.phaseTiers[id]) phaseTiers += MachineConfigPhaseTierEdit(id)
      if (base.agentPhaseOverrides[agent]?.get(id) != updated.agentPhaseOverrides[agent]?.get(id)) {
        phaseOverrides += MachineConfigAgentPhaseOverrideEdit(agent, id)
      }
    }
  }
  return MachineConfigExecutionMatrixEditIntent(tierDirectives, phaseTiers, phaseOverrides)
}

private fun executionMatrixWithDrafts(
  base: ExecutionMatrixConfig,
  drafts: Map<String, Map<String, String>>,
  agent: String,
): ExecutionMatrixConfig {
  var matrix = base
  val (tierDrafts, phaseDrafts) = drafts.entries.partition { (rowKey, _) ->
    rowKey.endsWith(".reasoning") || rowKey.endsWith(".implementation")
  }
  (tierDrafts + phaseDrafts).forEach { (rowKey, fields) ->
    val id = rowKey.removePrefix("execution_matrix.agents.$agent.")
    if (id == rowKey) return@forEach
    val model = fields[ConfigFileKeys.MODEL].orEmpty()
    if (model.isBlank()) return@forEach
    val directive = ModelDirective(model, fields[ConfigFileKeys.EFFORT]?.ifBlank { null })
    val tier = ModelTier.entries.firstOrNull { it.name.equals(id, true) }
    if (tier != null) {
      matrix = matrix.copy(agents = matrix.agents + (agent to (matrix.agents[agent].orEmpty() + (tier to directive))))
    } else if (fields[ConfigFileKeys.TIER] != null) {
      val phaseTier = ModelTier.entries.firstOrNull { it.name.equals(fields[ConfigFileKeys.TIER], true) } ?: return@forEach
      val phaseOverrides = matrix.agentPhaseOverrides[agent].orEmpty().let { overrides ->
        if (id in base.agentPhaseOverrides[agent].orEmpty() || directive != matrix.agents[agent]?.get(phaseTier)) {
          overrides + (id to directive)
        } else {
          overrides - id
        }
      }
      matrix = matrix.copy(
        phaseTiers = matrix.phaseTiers + (id to phaseTier),
        agentPhaseOverrides = matrix.agentPhaseOverrides + (agent to phaseOverrides),
      )
    }
  }
  return matrix
}

private fun machineFieldPaths(rowKey: String, fields: Map<String, String>): Map<String, String> =
  fields.keys.associateWith { field ->
    when {
      rowKey.startsWith("compaction.phases.") -> "${rowKey}.$field"
      rowKey.startsWith("compaction.") -> "compaction.$field"
      rowKey == "telemetry.proxy_url" -> "telemetry.proxy_url"
      rowKey == "telemetry.batch_size" -> "telemetry.batch_size"
      rowKey.startsWith("execution_matrix.") -> rowKey
      else -> field
    }
  }

private fun ConfigEdit.fieldsForInvalidKey(keyPath: String): Set<String> {
  return fieldPaths.filterValues { fieldPath ->
    keyPath == fieldPath || keyPath.startsWith("$fieldPath.") || fieldPath.startsWith("$keyPath.")
  }.keys
}

private fun invalidOwners(rows: List<ConfigEdit>, keyPath: String): List<ConfigEdit> {
  val exact = rows.filter { row -> row.fieldPaths.values.any { it == keyPath } }
  if (exact.isNotEmpty()) return exact
  return rows.filter { row -> row.fieldsForInvalidKey(keyPath).isNotEmpty() }
}

private fun groupTitle(value: String): String = when (value) {
  "PLANNING" -> "Planning"
  "BUILD" -> "Build"
  "AUDIT" -> "Audit"
  "REVIEW" -> "Review"
  "VALIDATE_AND_QA" -> "Validate and QA"
  "SHIP" -> "Ship"
  "GOAL_FINISH" -> "Goal finish"
  else -> "Other"
}

private fun configDocumentPath(document: ConfigDocument<*>?): String? = when (document) {
  is ConfigDocument.Present<*> -> document.path
  is ConfigDocument.Absent -> document.path
  is ConfigDocument.Unreadable -> document.path
  null -> null
}

private fun unreadableDocument(
  source: ConfigDocument<*>?,
  path: String,
  keyPath: String?,
  reason: String,
): ConfigDocument.Unreadable = ConfigDocument.Unreadable(
  path,
  keyPath,
  reason,
  candidatePaths = when (source) {
    is ConfigDocument.Present<*> -> source.candidatePaths
    is ConfigDocument.Absent -> source.candidatePaths
    is ConfigDocument.Unreadable -> source.candidatePaths
    null -> listOf(path)
  },
  projectStorePath = when (source) {
    is ConfigDocument.Present<*> -> source.projectStorePath
    is ConfigDocument.Absent -> source.projectStorePath
    is ConfigDocument.Unreadable -> source.projectStorePath
    null -> null
  },
)

internal fun configPathTransitionAllowed(previous: ConfigDraftBasis?, next: ConfigDraftBasis?): Boolean {
  if (previous == null || next == null) return previous == next
  val oldPath = Path.of(previous.path).toAbsolutePath().normalize()
  val newPath = Path.of(next.path).toAbsolutePath().normalize()
  if (oldPath == newPath) return true
  val previousCandidates = previous.candidatePaths.map { Path.of(it).toAbsolutePath().normalize().toString() }.toSet()
  val nextCandidates = next.candidatePaths.map { Path.of(it).toAbsolutePath().normalize().toString() }.toSet()
  return previous.projectStorePath == next.projectStorePath && previousCandidates.isNotEmpty() &&
    previousCandidates == nextCandidates && oldPath.toString() in previousCandidates && newPath.toString() in previousCandidates
}

private fun configPathObservationAllowed(
  previous: ConfigDraftBasis,
  observation: ConfigDocument<*>?,
): Boolean =
  when (observation) {
    is ConfigDocument.Present -> configPathTransitionAllowed(
      previous,
      ConfigDraftBasis(
        observation.path,
        observation.fingerprint,
        observation.candidatePaths.toSet(),
        observation.projectStorePath,
      ),
    )
    is ConfigDocument.Absent -> configPathTransitionAllowed(
      previous,
      ConfigDraftBasis(
        observation.path,
        null,
        observation.candidatePaths.toSet(),
        observation.projectStorePath,
      ),
    )
    is ConfigDocument.Unreadable -> {
      val previousCandidates = previous.candidatePaths.map(::normalizedConfigPath).toSet()
      val observedCandidates = observation.candidatePaths.map(::normalizedConfigPath).toSet()
      val oldPath = normalizedConfigPath(previous.path)
      val unreadablePath = normalizedConfigPath(observation.path)
      previous.projectStorePath?.let(::normalizedConfigPath) == observation.projectStorePath?.let(::normalizedConfigPath) &&
        previousCandidates.isNotEmpty() && previousCandidates == observedCandidates &&
        oldPath in previousCandidates && unreadablePath in observedCandidates
    }
    null -> false
  }

private fun normalizedConfigPath(path: String): String = Path.of(path).toAbsolutePath().normalize().toString()

internal fun modelAgentSelectable(agent: dev.skillbill.desktop.core.domain.model.ConfigAgent): Boolean = agent.modelCapable
