package dev.skillbill.tray.core.data.runtime

import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import dev.skillbill.tray.core.data.port.BoardProjection
import dev.skillbill.tray.core.data.port.ControlAction
import dev.skillbill.tray.core.data.port.ControlRefusal
import dev.skillbill.tray.core.data.port.ControlResult
import dev.skillbill.tray.core.data.port.DetailProjection
import dev.skillbill.tray.core.data.port.InvalidDatabasePathException
import dev.skillbill.tray.core.data.port.OwnerRegistration
import dev.skillbill.tray.core.data.port.ProjectedWork
import dev.skillbill.tray.core.data.port.RuntimeWorkGateway
import dev.skillbill.tray.core.data.port.SnapshotReads
import dev.skillbill.tray.core.data.port.StoreObservation
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.DetailList
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.RunOptions
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import me.tatarka.inject.annotations.Inject
import skillbill.di.core.RuntimeComponent
import skillbill.engine.work.control.model.WorkControlAction
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkControlTarget
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.databaseMissing
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkBoardWindow
import skillbill.ports.idestatus.model.WorkDetailList
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.work.model.WorkStoreObservation
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.concurrent.Volatile
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/** The supported runtime surface for one selected database: projection, snapshot reads and control requests. */
@Inject
@SingleIn(DatabaseSessionScope::class)
@ContributesBinding(DatabaseSessionScope::class)
class RuntimeWorkAdapter(private val runtime: RuntimeComponent) : RuntimeWorkGateway {
  @Volatile
  private var resolvedPath: Path? = null

  override fun databasePath(): String = try {
    runtime.databaseSession.resolveDbPath().also { resolvedPath = it }.toString()
  } catch (failure: InvalidPathException) {
    throw InvalidDatabasePathException(failure)
  }

  override fun upgradeExistingStore() {
    runtime.databaseSchema.migrateExistingDatabase()
  }

  override fun observation(): StoreObservation = existing { runtime.databaseSession.read { it.workList.observation() } }
    .let { StoreObservation(it.storeId, it.revision) }

  override fun <T : Any> existingSnapshot(read: SnapshotReads.() -> T): T = existing { RuntimeSnapshotReads().read() }

  private fun <T : Any> existing(query: () -> T): T = runtime.databaseSession.readIfPresent { query() }
    ?: throw databaseMissing(requireNotNull(resolvedPath).toString(), DatabaseAccessOperation.READ)

  override fun observeOwners(ids: List<String>, observedAt: Instant, onExit: () -> Unit): OwnerRegistration {
    val registration = runtime.workObservationService.observeOwners(ids, observedAt.toJavaInstant()) { onExit() }
    return object : OwnerRegistration {
      override val nextLeaseExpiry: Instant? get() = registration.nextLeaseExpiry?.toKotlinInstant()

      override fun close() = registration.close()
    }
  }

  override fun request(item: WorkItem, action: ControlAction, agentId: String?, stillCurrent: () -> Boolean): ControlResult {
    val result = when (action) {
      ControlAction.PAUSE, ControlAction.STOP -> {
        val target = item.goalTarget() ?: return ControlResult.Stale
        val control = if (action == ControlAction.STOP) WorkControlAction.STOP else WorkControlAction.PAUSE
        runtime.workControlService.request(target, control) { stillCurrent() }
      }
      ControlAction.CANCEL -> {
        val target = item.runTarget() ?: return ControlResult.Stale
        runtime.workControlService.request(target, WorkControlAction.CANCEL)
      }
      ControlAction.LAUNCH -> {
        val target = item.runTarget() ?: return ControlResult.Stale
        runtime.workControlService.request(target, WorkControlAction.LAUNCH, agentId)
      }
    }
    return when (result) {
      WorkControlResult.Accepted -> ControlResult.Accepted
      WorkControlResult.IntentRecorded -> ControlResult.IntentRecorded
      WorkControlResult.ExitConfirmed -> ControlResult.ExitConfirmed
      WorkControlResult.TerminationAttempted -> ControlResult.TerminationAttempted
      is WorkControlResult.LaunchFailed -> ControlResult.LaunchFailed(result.reason)
      is WorkControlResult.Rejected -> ControlResult.Rejected(result.reason.name.lowercase(), result.reason.toRefusal())
    }
  }

  private fun WorkControlRejection.toRefusal(): ControlRefusal = when (this) {
    WorkControlRejection.AGENT_REQUIRED -> ControlRefusal.AGENT_REQUIRED
    WorkControlRejection.BRANCH_CHANGED -> ControlRefusal.BRANCH_CHANGED
    WorkControlRejection.BRANCH_UNAVAILABLE -> ControlRefusal.BRANCH_UNAVAILABLE
    WorkControlRejection.TERMINATION_INCOMPLETE -> ControlRefusal.TERMINATION_INCOMPLETE
    WorkControlRejection.OWNERSHIP_MISMATCH -> ControlRefusal.OWNERSHIP_MISMATCH
    WorkControlRejection.FOREIGN_HOST -> ControlRefusal.FOREIGN_HOST
    WorkControlRejection.PID_REUSED -> ControlRefusal.PID_REUSED
    WorkControlRejection.UNINSPECTABLE -> ControlRefusal.UNINSPECTABLE
    WorkControlRejection.IDENTITY_MISMATCH -> ControlRefusal.IDENTITY_MISMATCH
    else -> ControlRefusal.OTHER
  }

  private fun WorkItem.goalTarget(): WorkControlTarget? {
    val issue = issueKey ?: return null
    val fileObservation = databaseFile?.toObservation() ?: return null
    return WorkControlTarget(
      identity.id, identity.repositoryIdentity, Path.of(repositoryRoot), issue, executionId, statusRevision, statusStoreId,
      runSequence, updatedAt.toJavaInstant(), controlLeaseOwner, controlLeaseGeneration,
      databaseStoreId?.let { WorkStoreObservation(it, requireNotNull(databaseRevision)) }, fileObservation,
    )
  }

  private fun WorkItem.runTarget(): WorkRunTarget? = databaseFile?.let {
    WorkRunTarget(
      identity.id, identity.repositoryIdentity, Path.of(repositoryRoot), executionId, statusRevision,
      updatedAt.toJavaInstant(), it.toObservation(),
    )
  }

  private inner class RuntimeSnapshotReads : SnapshotReads {
    override fun projectBoard(ids: List<String>, retention: Retention, observedAt: Instant, pageSize: Int): BoardProjection {
      val projection = runtime.ideStatusService.projectBoardSelection(
        ids,
        WorkBoardRequest(
          WorkBoardWindow.entries.single { it.wireValue == retention.cliValue },
          observedAt.toJavaInstant(),
          pageSize,
        ),
      )
      return BoardProjection(
        items = projection.items.map {
          ProjectedWork(it.toDomain(), it.snapshot.problem != null, it.freshnessDeadline?.toKotlinInstant())
        },
        diagnosticReasons = projection.diagnostics.map { it.reason.wireValue }.distinct(),
        observation = projection.observation?.let { StoreObservation(it.storeId, it.revision) },
      )
    }

    override fun detail(
      identity: WorkIdentity,
      observedAt: Instant,
      cursor: String?,
      pageSize: Int,
      anchor: String?,
      list: DetailList?,
    ): DetailProjection {
      val result = runtime.ideStatusService.detail(
        WorkDetailRequest(
          identity.id,
          observedAt = observedAt.toJavaInstant(),
          cursor = cursor,
          pageSize = pageSize,
          list = list?.let { WorkDetailList.valueOf(it.name) },
          anchor = anchor,
        ),
      )
      val options = result.item?.let { work ->
        val domain = work.snapshot.toDomain(work.repositoryRoot, work.finishedAt?.toKotlinInstant(), work.finishedAtSource?.wireValue)
          .copy(databaseFile = work.databaseFileObservation?.toDomain())
        domain.runTarget()?.let(runtime.workControlService::options)
      }
      val detail: Detail? = result.item?.toDomain(result.observedAt.toKotlinInstant(), null)?.let { mapped ->
        mapped.copy(item = mapped.item.withCheckedOutBranch(runtime.checkedOutBranchSource))
      }
      return DetailProjection(
        problemCode = result.problem?.code?.wireValue,
        detail = detail,
        runtimeCursor = result.nextCursor,
        listCursors = result.listCursors.entries.associate { DetailList.valueOf(it.key.name) to it.value },
        listAnchors = result.item?.listAnchors.orEmpty().entries.associate { DetailList.valueOf(it.key.name) to it.value },
        moreLists = result.item?.moreLists?.map { DetailList.valueOf(it.name) }.orEmpty(),
        runOptions = options?.let {
          RunOptions(it.canCancel, it.canLaunch, it.launchLabel, it.agentId, it.agentChoices, it.unavailableReason)
        },
      )
    }
  }
}
