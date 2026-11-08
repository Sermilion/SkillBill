package dev.skillbill.desktop.core.data.usage.codex.model

import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageSubject

data class CodexVerifiedSubjectConfirmation(
  val reservationId: String,
  val subject: VerifiedUsageSubject,
)

data class CodexVerifiedUsageObservation(
  val reservationId: String,
  val result: UsageResult,
)
