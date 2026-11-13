package dev.skillbill.desktop.core.domain.model

import kotlin.time.Instant

enum class QaStepState { Pending, Running, Passed, Failed, Skipped }
enum class QaTargetState { Pending, Running, Ready, Skipped }

data class QaChecklist(
  val invocationDirectory: String? = null,
  val reportPath: String? = null,
  val testedCommit: String? = null,
  val criteriaSource: String? = null,
  val targets: List<QaChecklistTarget> = emptyList(),
  val planRegisteredAt: Instant? = null,
  val cases: List<QaChecklistCase> = emptyList(),
  val findings: List<QaChecklistFinding> = emptyList(),
  val lastStepUpdateAt: Instant? = null,
)

data class QaChecklistTarget(
  val name: String,
  val state: QaTargetState,
  val detail: String? = null,
  val updatedAt: Instant? = null,
)

data class QaChecklistCase(val id: String, val title: String, val steps: List<QaChecklistStep>)

data class QaChecklistStep(
  val text: String,
  val state: QaStepState = QaStepState.Pending,
  val note: String? = null,
  val findingId: String? = null,
  val evidence: List<String> = emptyList(),
  val updatedAt: Instant? = null,
)

data class QaChecklistFinding(val id: String, val title: String, val recordedAt: Instant? = null)
