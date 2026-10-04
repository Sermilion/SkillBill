package skillbill.application.review.parallel.runner

import skillbill.application.review.model.ParallelCodeReviewReportContract
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.review.context.model.hunk.structuredString
import skillbill.scaffold.model.PlatformManifest

internal object ParallelCodeReviewRunnerParentPrompt {
  fun build(
    selected: List<ReviewSpecialistLaunchRequest>,
    routedManifests: List<PlatformManifest>,
    agentId: String,
    reportContract: ParallelCodeReviewReportContract = ParallelCodeReviewReportContract.DEFAULT,
  ): String =
    if (reportContract == ParallelCodeReviewReportContract.STANDALONE_REPORT_ONLY) {
      buildStandalone(selected, routedManifests, agentId)
    } else {
      buildDefault(selected, routedManifests, agentId)
    }

  private fun buildDefault(
    selected: List<ReviewSpecialistLaunchRequest>,
    routedManifests: List<PlatformManifest>,
    agentId: String,
  ): String =
    buildString {
      append(modeFraming())
      appendCursorDelegatedFanOut(selected, agentId)
      appendLine("Detected stack: ${routedManifests.joinToString("+") { it.slug }.ifBlank { "generic" }}")
      val rubricLabel =
        selected.joinToString { launch ->
          val decision = launch.assignment.laneDecision
          "${decision.specialistSkillName}" +
            "[paths=${launch.assignment.assignedPaths.joinToString(",") { structuredString(it) }};" +
            "add-ons=${decision.addOns.joinToString("+").ifBlank { "none" }};" +
            "origins=${decision.originLayerChains.joinToString("|") { it.joinToString("->") }}]"
        }.ifBlank { "code-review" }
      appendLine("Authoritative routed rubric identities: $rubricLabel")
      selected.forEach { launch ->
        val decision = launch.assignment.laneDecision
        appendLine()
        appendLine("## Resolved rubric: ${decision.specialistSkillName}")
        appendLine("Owned paths: ${launch.assignment.assignedPaths.joinToString(",") { structuredString(it) }}")
        launch.rubrics.forEach { rubric -> appendLine(rubric.body) }
      }
      appendLine(
        "Use the assigned bundle below as authoritative. Fetch every body through the bound broker " +
          "by calling read_evidence with an owned repository-relative path exactly as spelled in " +
          "'Owned paths'. The evidence_locator store_path and payload_file identify a hunk inside " +
          "the broker's own store; they are not read_evidence arguments and passing one is refused.",
      )
      appendLine(PARALLEL_REVIEW_DELEGATED_DEPTH_DIRECTIVE)
      appendLine(
        "Return free-form review prose and end with an explicit verdict line: " +
          "`verdict: approved` or `verdict: changes_requested` (needs_fix is accepted as changes_requested). " +
          "There is no findings-register format gate and no $PARALLEL_REVIEW_NO_FINDINGS_TOKEN requirement — " +
          "missing or imperfect register lines never fail the review.",
      )
      appendLine(
        "When you have concrete defects, also emit optional `[F-XXX]` register lines so claim " +
          "verification can re-check them: " +
          "'[F-XXX] Severity | Confidence | specialist=<skill name from Resolved rubric> | " +
          "commits=<sha>[,<sha>] | path=\"<repo-relative path>\" | line=<positive integer> | description'. " +
          "Use only the bare skill name for specialist — never copy the [paths=...;add-ons=...;origins=...] " +
          "annotation from the routed rubric catalog. Imperfect lines remain part of the prose result " +
          "and never block settlement; parsed lines are optional verification enrichment.",
      )
      appendReviewLearnings(selected)
      appendLine()
      selected.forEach { launch ->
        val decision = launch.assignment.laneDecision
        appendLine("## Assigned bundle: ${decision.specialistSkillName}")
        appendLine("Owned paths: ${launch.assignment.assignedPaths.joinToString(",") { structuredString(it) }}")
        appendAssignedBundleEvidence(launch)
      }
    }

  private fun buildStandalone(
    selected: List<ReviewSpecialistLaunchRequest>,
    routedManifests: List<PlatformManifest>,
    agentId: String,
  ): String =
    buildString {
      appendLine(
        "Review the already resolved assignment in report-only mode. " +
          "Do not edit, stage, commit, amend, or reset files.",
      )
      appendLine("Do not launch skill-bill phase review or skill-bill code-review recursively.")
      appendLine(
        "Return a findings register using '[F-001] Severity | Confidence | path/File.kt:12 | defect description', " +
          "or the exact line NO_FINDINGS when empty.",
      )
      appendLine("End with exactly one canonical verdict line: verdict: approved or verdict: changes_requested.")
      appendCursorDelegatedFanOut(selected, agentId)
      appendLine("Detected stack: ${routedManifests.joinToString("+") { it.slug }.ifBlank { "generic" }}")
      appendLine(
        "Authoritative routed rubric identities: " +
          selected.joinToString { requireNotNull(it.assignment.laneDecision.specialistSkillName) },
      )
      appendLine(
        "Apply the report-only restrictions above to every specialist assignment, including provider-native lanes.",
      )
      selected.forEach { launch ->
        val decision = launch.assignment.laneDecision
        appendLine()
        appendLine("## Resolved rubric: ${decision.specialistSkillName}")
        appendLine("Owned paths: ${launch.assignment.assignedPaths.joinToString(",") { structuredString(it) }}")
        appendLine("This assignment is report-only. Do not edit, stage, commit, amend, or reset files.")
        launch.rubrics.forEach { rubric -> appendLine(rubric.body) }
        appendLine("Follow the report-only rule above even when a rubric describes a concrete fix.")
      }
      appendLine(
        "Use the assigned bundle below as authoritative. Fetch every body through the bound broker " +
          "by calling read_evidence with an owned repository-relative path exactly as spelled in " +
          "'Owned paths'. The evidence_locator store_path and payload_file identify a hunk inside " +
          "the broker's own store; they are not read_evidence arguments and passing one is refused.",
      )
      appendLine(PARALLEL_REVIEW_DELEGATED_DEPTH_DIRECTIVE)
      appendReviewLearnings(selected)
      selected.forEach { launch ->
        appendLine()
        appendLine("## Assigned bundle: ${launch.assignment.laneDecision.specialistSkillName}")
        appendLine("Do not edit, stage, commit, amend, or reset files while reviewing this assignment.")
        appendAssignedBundleEvidence(launch)
      }
      appendLine()
      appendLine("Return recommendations only. Do not apply fixes or modify the repository.")
    }

  private fun StringBuilder.appendReviewLearnings(selected: List<ReviewSpecialistLaunchRequest>) {
    val learnings =
      selected
        .flatMap { it.assignment.learnings }
        .distinctBy { it.learningId }
        .sortedBy { it.learningId }
    if (learnings.isEmpty()) return
    appendLine()
    appendLine("## Review learnings")
    appendLine(PARALLEL_REVIEW_LEARNINGS_DIRECTIVE)
    appendLine(PARALLEL_REVIEW_DELEGATED_LEARNINGS_DIRECTIVE)
    learnings.forEach { learning ->
      appendLine("- ${learning.learningId} (${learning.source}): ${structuredString(learning.title)}")
      learning.ruleText.replace("\r\n", "\n").lineSequence().forEach { appendLine("  $it") }
    }
  }

  private fun StringBuilder.appendCursorDelegatedFanOut(
    selected: List<ReviewSpecialistLaunchRequest>,
    agentId: String,
  ) {
    if (agentId != "cursor") return
    val nativeLanes =
      selected
        .filter { it.workerKind == ReviewWorkerKind.PROVIDER_NATIVE }
        .mapNotNull { it.logicalWorkerName }
        .distinct()
    if (nativeLanes.isEmpty()) return
    appendLine()
    appendLine(
      "Launch these specialist lanes in parallel in one instruction: " +
        nativeLanes.joinToString(", ") +
        ". Invoke each lane with one /name line:",
    )
    nativeLanes.forEach { logicalName -> appendLine("/$logicalName") }
  }

  private fun modeFraming(): String =
    buildString {
      appendLine("Run a skill-bill phase review in delegated mode via the routed specialist fan-out.")
      appendLine("Resolved execution mode: delegated")
      appendLine(
        "Depth: full. Launch one specialist worker per resolved rubric below. Pass each specialist's " +
          "raw return through unchanged — do not require a register shape from them. You alone author " +
          "the final review prose and verdict from whatever they returned.",
      )
    }

  private fun StringBuilder.appendAssignedBundleEvidence(launch: ReviewSpecialistLaunchRequest) {
    parallelCodeReviewGovernedLaunchFor(launch).deliveredEntries.forEach { entry ->
      val hunk = entry.hunk
      val locator = hunk.evidenceLocator
      appendLine(
        "### Commit ${structuredString(entry.commitSha)} (order=${entry.orderIndex}, " +
          "path=${structuredString(hunk.path)})",
      )
      appendLine("Subject: ${structuredString(entry.subject.replace("\r\n", "\n"))}")
      appendLine("hunk_id: ${hunk.hunkId}")
      appendLine("spans: -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount}")
      appendLine("content_digest: ${hunk.contentDigest}")
      appendLine(
        "evidence_locator: store_path=${structuredString(locator.storePath)} " +
          "payload_file=${structuredString(locator.payloadFile)} " +
          "hunk_header=${structuredString(locator.hunkHeader)}",
      )
    }
  }
}
