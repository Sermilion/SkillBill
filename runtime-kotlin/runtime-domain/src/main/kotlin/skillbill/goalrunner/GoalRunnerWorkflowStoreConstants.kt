package skillbill.goalrunner

import java.time.Duration

private const val STALENESS_EVIDENCE_WINDOW_MINUTES: Long = 30
val STALENESS_EVIDENCE_WINDOW: Duration = Duration.ofMinutes(STALENESS_EVIDENCE_WINDOW_MINUTES)
