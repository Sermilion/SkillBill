package dev.skillbill.desktop.core.domain.model

enum class QaAssertionOutcome { Passed, Failed, NotExercised }

data class QaRunAssertion(
  val outcome: QaAssertionOutcome,
  val title: String,
  val detail: String,
  val evidencePaths: List<String> = emptyList(),
)

data class QaRunTemporaryEdit(val path: String?, val detail: String, val notRestored: Boolean)

/** One target kind's coverage line; [verdict] and [reason] are runtime wire tokens. */
data class QaRunCoverage(val kind: String, val verdict: String, val reason: String? = null, val backend: String? = null)

data class QaRunRetainedResource(val kind: String, val identity: String)

data class QaRunFinding(
  val id: String,
  val title: String,
  val observed: String? = null,
  val expected: String? = null,
  val steps: String? = null,
  val evidencePaths: List<String> = emptyList(),
)

/** What a QA report declared. Null on a run recorded before the format change; empty [assertions] mean an untagged report. */
data class QaRunDetails(
  val reportPath: String? = null,
  val invocationDirectory: String? = null,
  val skipReason: String? = null,
  val assertions: List<QaRunAssertion> = emptyList(),
  val coverage: List<QaRunCoverage> = emptyList(),
  val retainedResources: List<QaRunRetainedResource> = emptyList(),
  val temporaryEdits: List<QaRunTemporaryEdit> = emptyList(),
  val findings: List<QaRunFinding> = emptyList(),
  val historyAvailability: String = "not_recorded",
  val occurrences: List<QaRunOccurrence> = emptyList(),
)

data class QaRunOccurrence(
  val occurrenceId: String,
  val kind: String,
  val phaseAttempt: Int?,
  val workspaceInvocationId: String?,
  val startedAt: String,
  val finishedAt: String?,
  val disposition: String,
  val testedTreeIdentity: String?,
  val resultReference: String?,
  val frameReference: String?,
  val frameAvailability: String,
  val screenshots: List<QaRunScreenshot>,
  val stages: List<QaRunStage>,
)

data class QaRunScreenshot(val label: String, val path: String, val recordedAt: String, val format: String)

data class QaRunStage(val name: String, val state: String, val startedAt: String?, val finishedAt: String?, val detail: String?)
