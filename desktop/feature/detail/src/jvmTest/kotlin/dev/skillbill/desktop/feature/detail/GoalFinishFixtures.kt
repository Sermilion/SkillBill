package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.PhaseCard
import dev.skillbill.desktop.core.domain.model.PhaseCardState

internal fun finishCard(id: String, state: PhaseCardState = PhaseCardState.Done) = PhaseCard(
  phaseId = id, state = state, round = null, startedAt = null, finishedAt = null, durationMillis = null,
  agentId = null, model = null, effort = null, blockedReason = null, files = emptyList(), edit = null,
  activityLabel = null, activityAt = null, output = null, pullRequestUrl = null, pullRequestNumber = null,
  ciCycles = emptyList(), repairedChecks = emptyList(), conflictBaseBranch = null, fixCommitSha = null,
)
