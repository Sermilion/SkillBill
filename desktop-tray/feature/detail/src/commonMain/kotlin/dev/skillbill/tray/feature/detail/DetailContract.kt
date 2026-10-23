package dev.skillbill.tray.feature.detail

import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.IncidentRecoveryPreview
import dev.skillbill.tray.core.domain.model.Incident
import dev.skillbill.tray.core.domain.model.DatabaseFileIdentity
import dev.skillbill.tray.core.domain.model.IncidentPrerequisite
import dev.skillbill.tray.core.domain.model.DetailList
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.ui.AppendState
import dev.skillbill.tray.core.ui.Feedback

enum class ControlKind { PAUSE, STOP, CANCEL, LAUNCH, RECOVER }

/** The one control request that may be in flight for a work item; a second request waits until this one settles. */
sealed interface ControlState {
  data object Idle : ControlState
  data class InProgress(val kind: ControlKind) : ControlState

  /** The last request was refused or failed; [feedbackId] is the notice that explains it, and a new request may start. */
  data class Refused(val kind: ControlKind, val problem: Problem, val feedbackId: Long) : ControlState
}

sealed interface DetailLoad {
  data object Loading : DetailLoad

  /** The first read failed, so there is no detail to show. */
  data class Failed(val problem: Problem) : DetailLoad

  /**
   * The detail is shown. [refreshing] means retained pages are being read again; [readError] is a failed refresh that kept the
   * detail on screen, which therefore may be behind the store.
   */
  data class Loaded(val detail: Detail, val refreshing: Boolean = false, val readError: Problem? = null) : DetailLoad
}

data class DetailUiState(
  val identity: WorkIdentity,
  val load: DetailLoad = DetailLoad.Loading,
  /** Lists whose next page is being fetched or failed; any other list is idle or exhausted according to its cursor. */
  val appends: Map<DetailList, AppendState> = emptyMap(),
  val control: ControlState = ControlState.Idle,
  /** Whether the item is live or finished recently enough for run controls to be offered; false until the detail lands. */
  val controllable: Boolean = false,
  val pendingFeedback: List<Feedback> = emptyList(),
  val recovery: RecoveryState = RecoveryState.Idle,
  val prerequisiteIdentity: RecoveryIdentity? = null,
  val confirmedPrerequisites: Set<String> = emptySet(),
) {
  val recoveryConfirmation: Boolean get() = recovery is RecoveryState.Confirming
  val recoveryPreviewLoading: Boolean get() = recovery is RecoveryState.Loading
  val recoveryPreview: IncidentRecoveryPreview? get() = (recovery as? RecoveryState.Confirming)?.snapshot?.preview

  /** Where the next page of [list] stands. */
  fun append(list: DetailList): AppendState {
    appends[list]?.let { return it }
    val loaded = load as? DetailLoad.Loaded ?: return AppendState.Exhausted
    return if (loaded.detail.cursors[list] == null) AppendState.Exhausted else AppendState.Idle
  }
}

data class RecoveryIdentity(
  val workIdentity: WorkIdentity,
  val workflowId: String?,
  val invocationId: String?,
  val databaseGeneration: Long,
  val databaseFile: DatabaseFileIdentity?,
  val databaseStoreId: String?,
  val incidentToken: String,
  val incidentRevision: Long,
  val prerequisites: List<IncidentPrerequisite>,
) {
  fun matches(item: WorkItem, incident: Incident): Boolean =
    workIdentity == item.identity && workflowId == item.workflowId && invocationId == item.invocationId &&
      databaseGeneration == item.databaseGeneration &&
      databaseFile == item.databaseFile && databaseStoreId == item.databaseStoreId &&
      incidentToken == incident.incidentToken && incidentRevision == incident.revision && prerequisites == incident.prerequisites
}

data class RecoveryRequest(
  val item: WorkItem,
  val identity: RecoveryIdentity,
  val confirmedPrerequisites: Set<String>,
)

data class RecoverySnapshot(val request: RecoveryRequest, val preview: IncidentRecoveryPreview)

sealed interface RecoveryState {
  data object Idle : RecoveryState
  data class Loading(val request: RecoveryRequest) : RecoveryState
  data class Confirming(val snapshot: RecoverySnapshot) : RecoveryState
  data class Applying(val snapshot: RecoverySnapshot) : RecoveryState
}

sealed interface DetailAction {
  data class LoadMore(val list: DetailList) : DetailAction
  data object Pause : DetailAction
  data object Stop : DetailAction
  data object Cancel : DetailAction
  data class Launch(val agentId: String?) : DetailAction
  data object Recover : DetailAction
  data class ConfirmPrerequisite(val kind: String, val checked: Boolean) : DetailAction
  data object ConfirmRecovery : DetailAction
  data object CancelRecovery : DetailAction
  data object Refresh : DetailAction
  data class AcknowledgeFeedback(val id: Long) : DetailAction
}

sealed interface DetailEffect {
  /** A launch registered a new attempt; the shell moves the selection there if [from] is still what is shown. */
  data class SelectSuccessor(val from: WorkIdentity, val successor: WorkIdentity) : DetailEffect
}
