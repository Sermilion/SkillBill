package skillbill.workflow.taskruntime.model.qa

import skillbill.contracts.qa.QA_PANEL_HISTORY_CONTRACT_VERSION

enum class QaAssertionOutcome(val wireValue: String) {
  PASSED("passed"),
  FAILED("failed"),
  NOT_EXERCISED("not_exercised"),
  ;

  companion object {
    fun fromWire(value: String?): QaAssertionOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

data class QaRunAssertion(
  val outcome: QaAssertionOutcome,
  val title: String,
  val detail: String,
  val evidencePaths: List<String> = emptyList(),
)

data class QaRunTemporaryEdit(
  val path: String?,
  val detail: String,
  val notRestored: Boolean,
)

data class QaRunCoverage(
  val kind: String,
  val verdict: String,
  val reason: String?,
  val backend: String?,
)

data class QaRunRetainedResource(
  val kind: String,
  val identity: String,
)

data class QaRunFinding(
  val id: String,
  val title: String,
  val observed: String?,
  val expected: String?,
  val steps: String?,
  val evidencePaths: List<String> = emptyList(),
)

data class QaRunDetails(
  val reportPath: String? = null,
  val invocationDirectory: String? = null,
  val skipReason: String? = null,
  val assertions: List<QaRunAssertion> = emptyList(),
  val coverage: List<QaRunCoverage> = emptyList(),
  val retainedResources: List<QaRunRetainedResource> = emptyList(),
  val temporaryEdits: List<QaRunTemporaryEdit> = emptyList(),
  val findings: List<QaRunFinding> = emptyList(),
  val preview: QaRunPreview? = null,
  val historyAvailability: String = "not_recorded",
  val occurrences: List<QaRunOccurrence> = emptyList(),
  val historyContractVersion: String = QA_PANEL_HISTORY_CONTRACT_VERSION,
) {
  fun withStartedOccurrence(started: QaRunOccurrence): QaRunDetails {
    if (occurrences.any { it.occurrenceId == started.occurrenceId }) return this
    val reconciled =
      occurrences.map { previous ->
        if (previous.isTerminal()) {
          previous
        } else {
          previous.copy(
            disposition = "interrupted",
            stages =
              previous.stages.map { stage ->
                if (stage.state == QaRunStageState.RUNNING.wireValue) {
                  stage.copy(state = QaRunStageState.INTERRUPTED.wireValue)
                } else {
                  stage
                }
              },
          )
        }
      }
    return copy(preview = preview?.copy(active = false), occurrences = reconciled + started)
  }
}

data class QaRunPreview(
  val invocationId: String,
  val invocationDirectory: String,
  val targets: List<String>,
  val active: Boolean,
)

data class QaRunOccurrence(
  val occurrenceId: String,
  val kind: String,
  val phaseAttempt: Int?,
  val workspaceInvocationId: String?,
  val startedAt: String,
  val finishedAt: String? = null,
  val disposition: String = "running",
  val testedTreeIdentity: String? = null,
  val resultReference: String? = null,
  val frameReference: String? = null,
  val frameAvailability: String = "not_captured",
  val screenshots: List<QaRunScreenshot> = emptyList(),
  val stages: List<QaRunStage> = emptyList(),
) {
  fun isTerminal(): Boolean = disposition != "running" && disposition != "execution_completed"

  fun finished(
    disposition: String,
    at: String,
  ): QaRunOccurrence =
    if (isTerminal()) {
      this
    } else {
      copy(
        disposition = disposition,
        finishedAt = finishedAt ?: at,
        stages =
          stages.map { stage ->
            if (stage.state == QaRunStageState.RUNNING.wireValue) {
              stage.copy(state = QaRunStageState.INTERRUPTED.wireValue, finishedAt = at)
            } else {
              stage
            }
          },
      )
    }
}

data class QaRunScreenshot(
  val label: String,
  val path: String,
  val recordedAt: String,
  val format: String,
)

data class QaRunStage(
  val name: String,
  val state: String,
  val startedAt: String? = null,
  val finishedAt: String? = null,
  val detail: String? = null,
)

enum class QaRunStageState(val wireValue: String) {
  RUNNING("running"),
  DONE("done"),
  WARN("warn"),
  SKIPPED("skipped"),
  INTERRUPTED("interrupted"),
  ;

  companion object {
    val reportable: List<QaRunStageState> = entries.filter { it != INTERRUPTED }
  }
}

enum class QaRunKind(val wireValue: String) {
  QA("qa"),
  REPAIR("qa_fix"),
}
