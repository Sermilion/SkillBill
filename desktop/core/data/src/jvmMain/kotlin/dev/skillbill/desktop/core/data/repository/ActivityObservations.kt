package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.data.runtime.readOrProblem
import dev.skillbill.desktop.core.domain.model.ActivityBlock
import dev.skillbill.desktop.core.domain.model.ActivityBlockKind
import dev.skillbill.desktop.core.domain.model.ActivityFact
import skillbill.engine.work.timeline.model.WorkActivityFact
import dev.skillbill.desktop.core.domain.model.ActivityGroup
import dev.skillbill.desktop.core.domain.model.ActivityGroupKind
import dev.skillbill.desktop.core.domain.model.ActivityPhase
import dev.skillbill.desktop.core.domain.model.ActivityPhaseEvent
import dev.skillbill.desktop.core.domain.model.ActivityResult
import dev.skillbill.desktop.core.domain.model.ActivityStatus
import dev.skillbill.desktop.core.domain.model.ReadProblem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import skillbill.di.core.RuntimeComponent
import skillbill.engine.work.timeline.model.WorkActivityResult
import kotlin.time.toKotlinInstant

class ActivityObservations internal constructor(
  private val runtime: RuntimeComponent,
  private val diagnostics: Diagnostics,
  private val readTimeline: suspend (String) -> WorkActivityResult,
) {
  fun observe(id: String, running: Boolean): Flow<ActivityResult> = flow {
    emit(ActivityResult.Loading)
    do {
      val result = when (val read = readOrProblem(runtime, diagnostics) { readTimeline(id) }) {
        is Outcome.Failure -> ActivityResult.Unavailable(read.error)
        is Outcome.Success -> read.value.toDomain()
      }
      if (result is ActivityResult.Unavailable) diagnostics.record("desktop_activity_read_failed")
      emit(result)
      if (running) delay(CHANGE_REFRESH_INTERVAL)
    } while (running)
  }.distinctUntilChanged()
}

internal fun WorkActivityResult.toDomain(): ActivityResult = when (this) {
  WorkActivityResult.NotFound -> ActivityResult.NotFound
  is WorkActivityResult.Unavailable -> ActivityResult.Unavailable(ReadProblem.Runtime(reason))
  is WorkActivityResult.Listed -> ActivityResult.Listed(groups.map { g ->
    ActivityGroup(
      g.id, g.subtaskId, g.name, ActivityStatus.valueOf(g.status.name), g.startedAt?.toKotlinInstant(),
      g.finishedAt?.toKotlinInstant(),
      g.phases.map { p -> ActivityPhase(p.phaseId, p.attempt, p.startedAt?.toKotlinInstant(), p.finishedAt?.toKotlinInstant(), p.agent, p.model, p.effort, p.summary, p.verdict) },
      g.blocks.map { block -> ActivityBlock(block.id, block.at.toKotlinInstant(), ActivityBlockKind.valueOf(block.kind.name), block.phaseId, block.attempt, block.reason, block.needsDecision) },
      g.before.map { it.toDomain() }, g.after.map { it.toDomain() }, ActivityGroupKind.valueOf(g.kind.name),
      phaseEvents = buildList {
        g.phases.forEach { phase ->
          phase.startedAt?.let { add(ActivityPhaseEvent(phase.phaseId, it.toKotlinInstant(), "Started · attempt ${phase.attempt}", false)) }
          phase.finishedAt?.let { add(ActivityPhaseEvent(phase.phaseId, it.toKotlinInstant(), "Settled · ${phase.verdict ?: phase.summary ?: "finished"}", false)) }
        }
        g.blocks.forEach { block -> block.reason?.let { add(ActivityPhaseEvent(block.phaseId, block.at.toKotlinInstant(), it, true)) } }
      }.sortedBy { it.at },
    )
  })
}

private fun WorkActivityFact.toDomain() = ActivityFact(name, detail, at?.toKotlinInstant(), agent, model, effort, summary, running)
