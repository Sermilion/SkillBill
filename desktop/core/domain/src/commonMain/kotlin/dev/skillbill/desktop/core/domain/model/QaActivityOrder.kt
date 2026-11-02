package dev.skillbill.desktop.core.domain.model

import kotlin.time.Instant

interface QaTimedActivity {
  val at: Instant?
}

fun qaExecutionsChronological(runs: List<ExecutionInstance>): List<ExecutionInstance> = runs.sortedBy { it.startedAt }

fun <T : QaTimedActivity> qaActivityNewestFirst(events: List<T>): List<T> = events.sortedByDescending { it.at }
