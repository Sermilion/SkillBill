package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.IncidentPrerequisite
import dev.skillbill.desktop.core.domain.model.OperationCardDismissOutcome
import dev.skillbill.desktop.core.domain.model.OperationCardView
import dev.skillbill.desktop.core.domain.model.OperationCardLookup
import dev.skillbill.desktop.core.domain.model.PurgeOutcome
import dev.skillbill.desktop.core.domain.model.ExportPreflightOutcome
import dev.skillbill.desktop.core.domain.model.ExportOutcome
import dev.skillbill.desktop.core.domain.model.ArchiveInspectOutcome
import dev.skillbill.desktop.core.domain.model.ImportOutcome
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.model.RecoveryOutcome
import dev.skillbill.desktop.core.domain.model.RecoveryPreview
import dev.skillbill.desktop.core.domain.model.RecoveryPreviewOutcome
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.flow.StateFlow

/** Run controls and goal writes. None changes a row; rows change when the commit signal re-reads the store. */
interface WorkControlRepository {
  /** The work keys (goal issue key, otherwise work id) with a launch in flight or waiting for the board, app-wide. */
  val reservations: StateFlow<Set<String>>

  suspend fun pauseAfterSubtask(item: WorkItem): ControlOutcome

  suspend fun pauseNow(item: WorkItem): ControlOutcome

  suspend fun cancel(item: WorkItem): ControlOutcome

  suspend fun launch(item: WorkItem, agentId: String?): ControlOutcome

  suspend fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlOutcome

  suspend fun startNewTask(request: NewTaskStart): NewTaskStartOutcome

  /** The stored approval card of an operation launch, or null while none is stored. */
  suspend fun operationCardForLaunch(launchId: String): OperationCardView?

  /** The stored approval card for [token], read again to revalidate a card restored from history. */
  suspend fun operationCard(token: String): OperationCardView?

  suspend fun revalidateOperationCard(token: String): OperationCardLookup

  suspend fun dismissOperationCard(token: String): OperationCardDismissOutcome

  suspend fun recoveryPreview(item: WorkItem, confirmedPrerequisites: Set<IncidentPrerequisite>): RecoveryPreviewOutcome

  suspend fun recover(item: WorkItem, preview: RecoveryPreview): RecoveryOutcome

  suspend fun purge(issueKey: String, repoRoot: String?): PurgeOutcome

  suspend fun preflightExport(issueKey: String, repoRoot: String?): ExportPreflightOutcome

  suspend fun export(issueKey: String, repoRoot: String?, outputPath: String): ExportOutcome

  suspend fun inspectArchive(archivePath: String, repoRoot: String?): ArchiveInspectOutcome

  suspend fun import(archivePath: String, repoRoot: String?, fingerprint: String): ImportOutcome
}
