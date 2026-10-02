package skillbill.application.idestatus

import me.tatarka.inject.annotations.Inject
import skillbill.application.getOrElseUnlessCooperative
import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.error.core.failureCodeLabel
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.idestatus.model.AgentActivityStamp
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.AgentActivityStampRepository
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Inject
class AgentActivityStampWriter(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val timeSource: TimeSource,
) {
  fun lazySink(
    resolveWorkflowId: () -> String?,
    parentWorkflowId: String?,
  ): AgentRunActivityStampSink =
    AgentRunActivityStampSink { label ->
      val workflowId =
        runCatching { resolveWorkflowId() }
          .getOrElseUnlessCooperative { null }
          ?.takeIf(String::isNotBlank)
          ?: return@AgentRunActivityStampSink
      record(
        StampContext(
          workflowId = workflowId,
          parentWorkflowId = parentWorkflowId?.takeIf(String::isNotBlank),
        ),
        label,
      )
    }

  fun sink(
    workflowId: String,
    parentWorkflowId: String?,
  ): AgentRunActivityStampSink {
    val context =
      StampContext(
        workflowId = workflowId,
        parentWorkflowId = parentWorkflowId?.takeIf(String::isNotBlank),
      )
    return AgentRunActivityStampSink { label -> record(context, label) }
  }

  fun recordEvidenceRead(
    workflowId: String,
    parentWorkflowId: String?,
  ) {
    record(
      StampContext(
        workflowId = workflowId,
        parentWorkflowId = parentWorkflowId?.takeIf(String::isNotBlank),
      ),
      AgentActivityLabel.EVIDENCE_READ,
    )
  }

  private fun record(
    context: StampContext,
    label: AgentActivityLabel,
  ) {
    if (context.workflowId.isBlank()) return
    val stampToPersist = nextStamp(context.workflowId, label, clock.instant())
    if (stampToPersist != null && persist(context, stampToPersist)) {
      synchronized(throttleState) {
        val latest = throttleState[context.workflowId] ?: return
        val lastPersisted = latest.lastPersistedStamp
        if (lastPersisted == null || stampToPersist.recordedAt.isAfter(lastPersisted.recordedAt)) {
          latest.lastPersistedStamp = stampToPersist
          latest.lastPersistMark = timeSource.markNow()
        }
      }
    }
  }

  private fun nextStamp(
    workflowId: String,
    label: AgentActivityLabel,
    now: Instant,
  ): AgentActivityStamp? =
    synchronized(throttleState) {
      val latest = throttleState.getOrPut(workflowId) { LatestStamp() }
      val lastPersisted = latest.lastPersistedStamp
      val lastPersistMark = latest.lastPersistMark
      when {
        lastPersisted != null && !now.isAfter(lastPersisted.recordedAt) -> null
        lastPersisted?.label == label &&
          now.toEpochMilli() - lastPersisted.recordedAt.toEpochMilli() < DEBOUNCE_WINDOW_MILLIS -> null
        label != AgentActivityLabel.EVIDENCE_READ &&
          lastPersistMark != null &&
          lastPersistMark.elapsedNow() < DEBOUNCE_WINDOW_MILLIS.milliseconds -> null
        else -> AgentActivityStamp(recordedAt = now, label = label)
      }
    }

  private fun persist(
    context: StampContext,
    stamp: AgentActivityStamp,
  ): Boolean {
    val outcome =
      runCatching {
        database.selfManagedWrite { unitOfWork ->
          writeStamp(unitOfWork.agentActivityStamps, context.workflowId, stamp)
          context.parentWorkflowId?.let { parentId ->
            writeStamp(unitOfWork.agentActivityStamps, parentId, stamp)
          }
        }
      }
    outcome.exceptionOrNull()?.rethrowIfCooperativeCancellationOrInterruption()
    val error = outcome.exceptionOrNull()
    if (error != null) {
      val cause =
        (error.message?.takeIf(String::isNotBlank) ?: error.failureCodeLabel() ?: error::class.simpleName.orEmpty())
          .take(MAX_DIAGNOSTIC_CAUSE_LENGTH)
      runCatching {
        diagnostics.warning(
          "seam=agent_activity_stamp_persist value_expected=persisted_stamp value_used=failed " +
            "workflow_id=${context.workflowId} label=${stamp.label.wireValue} cause=$cause",
        )
      }
      return false
    }
    return true
  }

  private fun writeStamp(
    repository: AgentActivityStampRepository,
    workflowId: String,
    stamp: AgentActivityStamp,
  ) {
    repository.record(workflowId, stamp)
  }

  private data class StampContext(
    val workflowId: String,
    val parentWorkflowId: String?,
  )

  private class LatestStamp {
    var lastPersistedStamp: AgentActivityStamp? = null
    var lastPersistMark: TimeMark? = null
  }

  private val throttleState =
    object : LinkedHashMap<String, LatestStamp>(
      INITIAL_TRACKED_WORKFLOWS,
      ACCESS_ORDER_LOAD_FACTOR,
      true,
    ) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LatestStamp>?): Boolean =
        size > MAX_TRACKED_WORKFLOWS
    }

  private companion object {
    const val DEBOUNCE_WINDOW_MILLIS: Long = 250L
    const val MAX_TRACKED_WORKFLOWS: Int = 512
    const val MAX_DIAGNOSTIC_CAUSE_LENGTH: Int = 256
    const val INITIAL_TRACKED_WORKFLOWS: Int = 16
    const val ACCESS_ORDER_LOAD_FACTOR: Float = 0.75f
  }
}
