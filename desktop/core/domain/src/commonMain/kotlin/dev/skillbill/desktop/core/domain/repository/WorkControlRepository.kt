package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.DecisionOutcome
import dev.skillbill.desktop.core.domain.model.IncidentPrerequisite
import dev.skillbill.desktop.core.domain.model.OperatorDecision
import dev.skillbill.desktop.core.domain.model.PurgeOutcome
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

  suspend fun recoveryPreview(item: WorkItem, confirmedPrerequisites: Set<IncidentPrerequisite>): RecoveryPreviewOutcome

  suspend fun recover(item: WorkItem, preview: RecoveryPreview): RecoveryOutcome

  suspend fun recordOperatorDecision(item: WorkItem, decision: OperatorDecision, instructions: String?): DecisionOutcome

  suspend fun purge(issueKey: String, repoRoot: String?): PurgeOutcome
}
