package skillbill.engine.goalrunner.assessment

import skillbill.engine.diagnostics.containableFailure
import skillbill.engine.featuretask.lifecycle.branch.protectedBranchName
import skillbill.engine.featuretask.lifecycle.subtask.parseGitPorcelainPaths
import skillbill.engine.featuretask.model.core.PhaseInstructions
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goalrunner.execution.support.isFeatureSpecPath
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.qa.GoalPostChangeValidationResult
import skillbill.engine.goalrunner.qa.GoalRunnerPostChangeValidation
import skillbill.error.core.catchingNonCancellation
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFix
import skillbill.goalrunner.assessment.model.GoalAssessmentFixStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentFixValidation
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitIdentityResult
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal class GoalAssessmentFixStep(
  private val services: GoalAssessmentRunServices,
  private val gitOperations: WorkflowGitOperations,
  private val validation: GoalRunnerPostChangeValidation,
) {
  fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    record: GoalAssessmentRecord,
    roundNumber: Int,
    fixSet: GoalAssessmentFixSet,
  ): GoalAssessmentRecord {
    val run = FixRun(services, state, request, roundNumber, record)
    var fix = run.round().fix ?: run.save(agentFix(run, fixSet))
    if (fix.status == GoalAssessmentFixStatus.COMPLETED && fix.validation == GoalAssessmentFixValidation.NOT_RUN) {
      fix = run.save(validated(run, fix))
    }
    if (fix.status == GoalAssessmentFixStatus.COMPLETED && fix.commitSha == null) {
      run.save(committed(run, fix))
    }
    return run.record
  }

  private fun agentFix(
    run: FixRun,
    fixSet: GoalAssessmentFixSet,
  ): GoalAssessmentFix {
    val before = dirtyPaths(run).orEmpty().toSet()
    val launched = launch(run, inputText(run, fixSet))
    val changed = dirtyPaths(run)?.filterNot(before::contains)
    return when {
      launched == null || changed == null ->
        GoalAssessmentFix(
          GoalAssessmentFixStatus.FAILED,
          changed.orEmpty(),
          null,
          GoalAssessmentFixValidation.NOT_RUN,
        )
      else -> {
        warnUnfixed(launched)
        val status =
          if (changed.isEmpty()) GoalAssessmentFixStatus.NO_CHANGE else GoalAssessmentFixStatus.COMPLETED
        GoalAssessmentFix(status, changed, null, GoalAssessmentFixValidation.NOT_RUN)
      }
    }
  }

  private fun launch(
    run: FixRun,
    text: String,
  ): String? {
    val phaseRequest =
      services.phaseRequest(
        run.state,
        run.request,
        SkeletonDefinition.GOAL_ASSESSMENT_FIX,
        PhaseInstructions(text, setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT_FIX)),
      )
    val branch = services.branchOf(run.state)
    val result =
      catchingNonCancellation { services.phaseRunEntry.runForGoal(phaseRequest, run.state.parentWorkflowId, branch) }
        .getOrElse { failure ->
          val contained = containableFailure(failure)
          services.warn("the fix agent failed: ${contained.javaClass.simpleName}: ${contained.message}", contained)
          return null
        }
    return when (result) {
      is PhaseRunResult.Completed -> (result.acceptedOutput ?: result.value).orEmpty()
      is PhaseRunResult.Blocked -> {
        services.warn("the fix agent stopped at '${result.stepId}': ${result.reason}")
        null
      }
    }
  }

  private fun warnUnfixed(output: String) {
    output.lineSequence().map(String::trim).filter { NOT_FIXED.matches(it) }.forEach {
      services.warn("the fix agent left a finding unfixed: $it")
    }
  }

  private fun validated(
    run: FixRun,
    fix: GoalAssessmentFix,
  ): GoalAssessmentFix {
    val phaseRequest = services.phaseRequest(run.state, run.request, SkeletonDefinition.VALIDATION, null)
    val branch = services.branchOf(run.state)
    val outcome =
      catchingNonCancellation { validation.run(phaseRequest, run.state.parentWorkflowId, branch) }
        .getOrElse { failure ->
          val contained = containableFailure(failure)
          services.warn("validation after the fix failed: ${contained.javaClass.simpleName}: ${contained.message}")
          GoalPostChangeValidationResult.Stopped("validation", contained.message.orEmpty())
        }
    val passed = outcome is GoalPostChangeValidationResult.Passed
    if (outcome is GoalPostChangeValidationResult.Stopped) {
      services.warn("validation after the fix stopped at '${outcome.stepId}': ${outcome.reason}")
    }
    return fix.copy(
      changedFiles = dirtyPaths(run) ?: fix.changedFiles,
      validation = if (passed) GoalAssessmentFixValidation.PASSED else GoalAssessmentFixValidation.FAILED,
    )
  }

  private fun committed(
    run: FixRun,
    fix: GoalAssessmentFix,
  ): GoalAssessmentFix {
    val round = run.round()
    val head = (gitOperations.headCommitSha(run.request.repoRoot) as? WorkflowGitOperationResult.Ok)?.value?.trim()
    return when {
      head.isNullOrEmpty() -> unrecorded(fix, "the head commit could not be read")
      !head.equals(round.headSha, ignoreCase = true) -> adopted(run, fix, round, head)
      else -> branchProblem(run)?.let { unrecorded(fix, it) } ?: commitRound(run, fix)
    }
  }

  private fun commitRound(
    run: FixRun,
    fix: GoalAssessmentFix,
  ): GoalAssessmentFix {
    val paths = dirtyPaths(run) ?: return unrecorded(fix, "the worktree status could not be read")
    if (paths.isEmpty()) return fix.copy(status = GoalAssessmentFixStatus.NO_CHANGE, changedFiles = emptyList())
    val intended = if (paths.toSet() == fix.changedFiles.toSet()) fix else run.save(fix.copy(changedFiles = paths))
    val staged = gitOperations.stagePaths(run.request.repoRoot, paths)
    if (staged !is WorkflowGitOperationResult.Ok) return unrecorded(intended, "staging failed: ${staged.error}")
    val subject = "${run.state.manifest.issueKey}: goal assessment round ${run.roundNumber}"
    return when (val commit = gitOperations.createCommit(run.request.repoRoot, subject)) {
      is WorkflowGitCommitResult.Committed -> intended.copy(commitSha = commit.commitSha.ifBlank { null })
      WorkflowGitCommitResult.NothingToCommit -> unrecorded(intended, "git found nothing to commit")
      is WorkflowGitCommitResult.Failed -> unrecorded(intended, "the commit failed: ${commit.error}")
    }
  }

  private fun adopted(
    run: FixRun,
    fix: GoalAssessmentFix,
    round: GoalAssessmentRound,
    head: String,
  ): GoalAssessmentFix {
    val identity = gitOperations.commitIdentity(run.request.repoRoot, head)
    val ownedParent =
      identity is WorkflowGitCommitIdentityResult.Found &&
        identity.identity.parentShas.singleOrNull()?.equals(round.headSha, ignoreCase = true) == true
    val listed = gitOperations.runtimePhaseChangedPathsBetweenCommits(run.request.repoRoot, round.headSha, head)
    val changed = (listed as? WorkflowGitNameListResult.Listed)?.names?.toSet()
    if (ownedParent && changed == fix.changedFiles.toSet()) return fix.copy(commitSha = head)
    return unrecorded(fix, "the head moved to $head and it is not the commit this round intended")
  }

  private fun unrecorded(
    fix: GoalAssessmentFix,
    reason: String,
  ): GoalAssessmentFix {
    services.warn("the goal assessment round commit was not made: $reason.")
    return fix
  }

  private fun branchProblem(run: FixRun): String? {
    val branch = services.branchOf(run.state).trim()
    protectedBranchName(branch)?.let { return "the round commit refuses protected branch '$it'" }
    val current = gitOperations.currentBranch(run.request.repoRoot)
    val currentBranch = (current as? WorkflowGitOperationResult.Ok)?.value?.trim()
    return when {
      branch.isEmpty() -> "the goal has no feature branch"
      currentBranch == null -> "the current branch could not be read: ${current.error}"
      currentBranch != branch -> "the round commit requires feature branch '$branch', not '$currentBranch'"
      else -> null
    }
  }

  private fun dirtyPaths(run: FixRun): List<String>? {
    val status = gitOperations.worktreeStatus(run.request.repoRoot)
    if (status !is WorkflowGitOperationResult.Ok) {
      services.warn("the worktree status could not be read: ${status.error}")
      return null
    }
    return parseGitPorcelainPaths(status.value).filterNot(::isFeatureSpecPath).distinct()
  }

  private fun inputText(
    run: FixRun,
    fixSet: GoalAssessmentFixSet,
  ): String {
    val findings = run.record.rounds.flatMap { it.findings }.filter { it.findingRef in fixSet.findingRefs }
    val regressions =
      run.round().regressions.filter { it.regressionRef in fixSet.regressionRefs }
        .joinToString("\n") { "- ${it.regressionRef}: ${it.evidence}" }
    val findingLines = findings.joinToString("\n", transform = ::findingText)
    return listOfNotNull(
      "# Goal assessment fix: ${run.state.manifest.issueKey}",
      findingLines.takeIf(String::isNotBlank)?.let { "## Findings to fix\n\n$it" },
      regressions.takeIf(String::isNotBlank)?.let { "## Transitional regressions to restore\n\n$it" },
    ).joinToString("\n\n")
  }

  private fun findingText(finding: GoalAssessmentFinding): String {
    val evidence = finding.verificationEvidence.takeIf(String::isNotBlank)?.let { " Evidence: $it" }.orEmpty()
    return "- ${finding.findingRef} (${finding.severity.wireValue}) ${finding.path}:${finding.line} - " +
      finding.failureScenario + evidence
  }

  private class FixRun(
    private val services: GoalAssessmentRunServices,
    val state: GoalRunnerManifestState,
    val request: GoalRunnerRunRequest,
    val roundNumber: Int,
    var record: GoalAssessmentRecord,
  ) {
    fun round(): GoalAssessmentRound = record.rounds.first { it.round == roundNumber }

    fun save(fix: GoalAssessmentFix): GoalAssessmentFix {
      record = record.copy(rounds = record.rounds.map { if (it.round == roundNumber) it.copy(fix = fix) else it })
      services.persist(state, record)
      return fix
    }
  }

  private companion object {
    val NOT_FIXED = Regex("""(GA|TR)-\d{3}: not fixed: .*""")
  }
}
