package dev.skillbill.tray.core.data.runtime

import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.domain.model.BoardArrangement
import dev.skillbill.tray.core.domain.model.TrayStatus
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.repository.WorkPolicyRepository
import me.tatarka.inject.annotations.Inject
import skillbill.idestatus.model.IdeStatusBoardEntry
import skillbill.idestatus.model.IdeStatusDisplayGroup
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusLiveSection
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.idestatus.model.IdeStatusWorkPolicy
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.toJavaInstant

/** Hands the runtime's own verdicts on each item back to the runtime's board arrangement and maps the result by identity. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeWorkPolicyRepository : WorkPolicyRepository {
  override fun arrange(items: List<WorkItem>): BoardArrangement {
    val byKey = items.associateBy { it.identity.id to it.identity.repositoryIdentity }
    val arranged = IdeStatusPolicy.arrange(items.map { it.toEntry() })
    fun List<IdeStatusBoardEntry>.resolve(): List<WorkIdentity> =
      mapNotNull { byKey[it.key to it.repositoryIdentity]?.identity }
    return BoardArrangement(
      trayStatus = TrayStatus.valueOf(arranged.trayStatus.name),
      running = arranged.running,
      needsYou = arranged.needsYou,
      doneWithinDay = arranged.doneWithinDay,
      goals = arranged.goals.resolve(),
      livePhases = arranged.livePhases.resolve(),
      finishedPhases = arranged.finishedPhases.resolve(),
      liveOperations = arranged.liveOperations.resolve(),
      finishedOperations = arranged.finishedOperations.resolve(),
    )
  }
}

private fun WorkItem.toEntry(): IdeStatusBoardEntry =
  IdeStatusBoardEntry(
    key = identity.id,
    repositoryIdentity = identity.repositoryIdentity,
    lifecycle = IdeStatusLifecycleState.valueOf(lifecycle.name),
    policy =
      IdeStatusWorkPolicy(
        liveSection = liveSection?.let { IdeStatusLiveSection.valueOf(it.name) },
        controlEligible = controlEligible,
        pauseEligible = pauseEligible,
        requiresAttention = requiresAttention,
        displayGroup = displayGroup?.let { IdeStatusDisplayGroup.valueOf(it.name) },
        displayPriority = displayPriority,
        running = running,
        recentlyFinishedPhase = recentlyFinishedPhase,
        doneWithinDay = doneWithinDay,
        membershipDeadline = membershipDeadline?.toJavaInstant(),
      ),
  )
