package skillbill.engine.featuretask.qa.checklist

import skillbill.error.featuretask.qa.qaProgressFailed
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandaloneQaChecklistCase
import skillbill.ports.idestatus.model.StandaloneQaChecklistFinding
import skillbill.ports.idestatus.model.StandaloneQaChecklistStep
import skillbill.ports.idestatus.model.StandaloneQaChecklistTarget
import skillbill.ports.idestatus.model.StandaloneQaTargetState
import java.time.Instant

internal data class QaChecklistMerged(
  val checklist: StandaloneQaChecklist,
  val rejectedEvidence: List<String> = emptyList(),
)

internal object QaChecklistMerge {
  private const val MAX_CASES = 100
  private const val MAX_STEPS = 100
  private const val MAX_TARGETS = 32
  private const val MAX_FINDINGS = 200
  private const val MAX_EVIDENCE = 20
  private const val MAX_ID = 64
  private const val MAX_LABEL = 500
  private const val MAX_DETAIL = 1000
  private const val MAX_PATH = 1024
  private val PATH_SEPARATOR = Regex("[/\\\\]")

  fun seeded(
    current: StandaloneQaChecklist,
    seed: QaChecklistSeed,
  ): QaChecklistMerged =
    merged(
      current.copy(
        invocationDirectory = seed.invocationDirectory,
        reportPath = seed.reportPath,
        testedCommit = seed.testedCommit,
        criteriaSource = seed.criteriaSource,
        targets = seed.targets.map { StandaloneQaChecklistTarget(it, StandaloneQaTargetState.PENDING) },
      ),
    )

  fun withPlan(
    current: StandaloneQaChecklist,
    plan: List<StandaloneQaChecklistCase>,
    now: Instant,
  ): QaChecklistMerged {
    val previous = current.cases.associateBy { it.id }
    val cases =
      plan.map { case ->
        val earlier = previous[case.id]?.steps.orEmpty()
        case.copy(
          steps =
            case.steps.mapIndexed { index, step ->
              earlier.getOrNull(index)?.takeIf { it.text == step.text } ?: step
            },
        )
      }
    return merged(current.copy(cases = cases, planRegisteredAt = now))
  }

  fun withStep(
    current: StandaloneQaChecklist,
    report: QaStepReport,
    now: Instant,
  ): QaChecklistMerged {
    val case = current.cases.firstOrNull { it.id == report.caseId } ?: throw qaProgressFailed("the case is unknown")
    val index = report.stepNumber - 1
    val steps = case.steps
    val step = steps.getOrNull(index) ?: throw qaProgressFailed("the step is unknown")
    val (kept, dropped) = report.evidence.partition { isInsideInvocation(it, current.invocationDirectory) }
    val updated =
      step.copy(
        status = report.status,
        note = report.note ?: step.note.takeIf { step.status == report.status },
        findingId = report.findingId ?: step.findingId,
        evidence = (step.evidence + kept).distinct(),
        updatedAt = now,
      )
    val cases =
      current.cases.map { candidate ->
        if (candidate.id != case.id) {
          candidate
        } else {
          candidate.copy(steps = candidate.steps.mapIndexed { i, existing -> if (i == index) updated else existing })
        }
      }
    return merged(
      current.copy(cases = cases, findings = withFinding(current.findings, report, now), lastStepUpdateAt = now),
      dropped,
    )
  }

  fun withTarget(
    current: StandaloneQaChecklist,
    report: QaTargetReport,
    now: Instant,
  ): QaChecklistMerged {
    val existing = current.targets.firstOrNull { it.name == report.name }
    val updated =
      StandaloneQaChecklistTarget(
        name = report.name,
        state = report.state,
        detail = report.detail ?: existing?.detail?.takeIf { existing.state == report.state },
        updatedAt = now,
      )
    val targets =
      if (existing == null) {
        current.targets + updated
      } else {
        current.targets.map { if (it.name == report.name) updated else it }
      }
    return merged(current.copy(targets = targets))
  }

  private fun withFinding(
    findings: List<StandaloneQaChecklistFinding>,
    report: QaStepReport,
    now: Instant,
  ): List<StandaloneQaChecklistFinding> {
    val id = report.findingId ?: return findings
    val existing = findings.firstOrNull { it.id == id }
    val title = report.findingTitle ?: existing?.title ?: id
    val updated = StandaloneQaChecklistFinding(id, title, existing?.recordedAt ?: now)
    return if (existing == null) findings + updated else findings.map { if (it.id == id) updated else it }
  }

  private fun isInsideInvocation(
    path: String,
    directory: String?,
  ): Boolean = directory != null && path.startsWith("$directory/") && path.split(PATH_SEPARATOR).none { it == ".." }

  private fun merged(
    checklist: StandaloneQaChecklist,
    rejectedEvidence: List<String> = emptyList(),
  ): QaChecklistMerged {
    requireWithinBounds(checklist)
    return QaChecklistMerged(checklist, rejectedEvidence)
  }

  private fun requireWithinBounds(checklist: StandaloneQaChecklist) {
    requireCount(checklist.cases.size, MAX_CASES, "cases")
    requireCount(checklist.targets.size, MAX_TARGETS, "targets")
    requireCount(checklist.findings.size, MAX_FINDINGS, "findings")
    listOfNotNull(checklist.invocationDirectory, checklist.reportPath).forEach { requirePath(it) }
    checklist.criteriaSource?.let { requireLength(it, MAX_ID, "criteria source") }
    checklist.targets.forEach { target ->
      requireText(target.name, MAX_ID, "target name")
      target.detail?.let { requireLength(it, MAX_DETAIL, "target detail") }
    }
    checklist.findings.forEach { finding ->
      requireText(finding.id, MAX_ID, "finding id")
      requireText(finding.title, MAX_LABEL, "finding title")
    }
    checklist.cases.forEach(::requireCaseWithinBounds)
  }

  private fun requireCaseWithinBounds(case: StandaloneQaChecklistCase) {
    requireText(case.id, MAX_ID, "case id")
    requireText(case.title, MAX_LABEL, "case title")
    requireCount(case.steps.size, MAX_STEPS, "steps in a case")
    case.steps.forEach(::requireStepWithinBounds)
  }

  private fun requireStepWithinBounds(step: StandaloneQaChecklistStep) {
    requireText(step.text, MAX_LABEL, "step text")
    step.note?.let { requireLength(it, MAX_DETAIL, "step note") }
    requireCount(step.evidence.size, MAX_EVIDENCE, "evidence paths on a step")
    step.evidence.forEach { requirePath(it) }
  }

  private fun requirePath(path: String) = requireLength(path, MAX_PATH, "path")

  private fun requireCount(
    count: Int,
    limit: Int,
    label: String,
  ) {
    if (count > limit) throw qaProgressFailed("more than $limit $label")
  }

  private fun requireText(
    value: String,
    limit: Int,
    label: String,
  ) {
    if (value.isBlank()) throw qaProgressFailed("the $label is blank")
    requireLength(value, limit, label)
  }

  private fun requireLength(
    value: String,
    limit: Int,
    label: String,
  ) {
    if (value.length > limit) throw qaProgressFailed("the $label is longer than $limit characters")
  }
}
