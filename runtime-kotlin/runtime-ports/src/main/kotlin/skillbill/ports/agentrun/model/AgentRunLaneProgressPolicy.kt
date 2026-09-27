package skillbill.ports.agentrun.model

import kotlin.time.Duration

const val READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES = 30L

fun SkillRunRequest.withBoundedLaneProgress(
  bound: Duration,
  probe: AgentRunProgressProbe,
): SkillRunRequest = copy(progressIdleTimeout = bound, progressProbe = probe, readOnlyPhase = false)
