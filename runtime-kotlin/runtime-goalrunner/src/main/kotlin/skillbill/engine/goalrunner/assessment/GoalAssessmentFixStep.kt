package skillbill.engine.goalrunner.assessment

import skillbill.engine.diagnostics.containableFailure
import skillbill.engine.featuretask.lifecycle.branch.protectedBranchName
import skillbill.engine.featuretask.lifecycle.subtask.parseGitPorcelainPaths
import skillbill.engine.featuretask.model.core.PhaseInstructions
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.execution.support.isFeatureSpecPath
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.qa.GoalPostChangeValidationResult
import skillbill.engine.goalrunner.qa.GoalRunnerPostChangeValidation
import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
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
    return try {
      execute(run, fixSet)
    } catch (failure: SkillBillRuntimeException) {
      if (failure.code != GoalAssessmentFailureCode.PERSISTENCE) throw failure
      services.warn("the goal assessment repair could not be persisted: ${failure.message}")
      run.record
    }
  }

  private fun execute(
    run: FixRun,
    fixSet: GoalAssessmentFixSet,
  ): GoalAssessmentRecord {
    if (!preparePlan(run, fixSet)) return run.record
    val fix = run.round().fix ?: run.save(agentFix(run, fixSet))
    if (fix.status == GoalAssessmentFixStatus.COMPLETED) return finishFix(run, fix)
    return run.record
  }

  private fun preparePlan(
    run: FixRun,
    fixSet: GoalAssessmentFixSet,
  ): Boolean {
    if (run.round().fix == null && run.round().repairPlan == null) {
      if (branchProblem(run) != null || !matchesAssessedHead(run) || dirtyPaths(run)?.isNotEmpty() != false) {
        services.warn("repair planning stopped because the feature branch, assessed head, or worktree is not ready")
        return false
      }
      val plan =
        launch(
          run,
          inputText(run, fixSet).replace("# Goal assessment fix:", "# Goal assessment repair plan:") +
            "\n\nPrepare a read-only repair plan for this selected item. Use its reference, kind, test and guarded " +
            "locations, failure scenario, and verifier evidence. Name the files, changes, and independent source " +
            "verification. Leave speculation unconfirmed. A test-value repair may rewrite or delete a useless test " +
            "when evidence supports it, but must preserve real regression coverage and product behavior. " +
            "Do not edit files, build, test, validate, commit or push. Return ordinary prose.",
          SkeletonDefinition.GOAL_ASSESSMENT,
        )?.takeIf(String::isNotBlank) ?: return false
      run.savePlan(plan)
    }
    return true
  }

  private fun finishFix(
    run: FixRun,
    fix: GoalAssessmentFix,
  ): GoalAssessmentRecord {
    if (fix.validation == GoalAssessmentFixValidation.FAILED) return run.record
    if (fix.validation == GoalAssessmentFixValidation.PASSED && fix.commitSha != null) {
      val problem = branchProblem(run)
      val head = gitOperations.headCommitSha(run.request.repoRoot) as? WorkflowGitOperationResult.Ok
      if (problem != null || head?.value?.trim()?.equals(fix.commitSha, ignoreCase = true) != true) {
        services.warn(problem ?: "the recorded repair commit is no longer the repository head")
        return run.record.copy(exitReason = GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE)
      }
      return run.record
    }
    return validateAndCommit(run, fix)
  }

  private fun validateAndCommit(
    run: FixRun,
    initial: GoalAssessmentFix,
  ): GoalAssessmentRecord {
    var fix = initial
    if (fix.validation == GoalAssessmentFixValidation.NOT_RUN) {
      if (fix.commitSha != null) {
        services.warn("a recorded repair commit has no saved post-change validation result")
        return run.record
      }
      val problem = branchProblem(run)
      val paths = dirtyPaths(run)
      if (problem != null || !matchesAssessedHead(run) || paths?.toSet() != fix.changedFiles.toSet()) {
        services.warn(problem ?: "the saved repair paths or assessed head no longer match the worktree")
        return run.record.copy(exitReason = GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE)
      }
      fix = run.save(validated(run, fix))
      if (fix.validation != GoalAssessmentFixValidation.PASSED) return run.record
    }
    if (fix.commitSha == null) run.save(committed(run, fix))
    return run.record
  }

  private fun agentFix(
    run: FixRun,
    fixSet: GoalAssessmentFixSet,
  ): GoalAssessmentFix {
    repairReadinessProblem(run)?.let { return failed(run, it) }
    val before = dirtyPaths(run) ?: return failed(run, "the worktree status could not be read before repair")
    if (before.isNotEmpty()) {
      return failed(run, "pre-existing non-spec worktree changes prevent a focused repair", before)
    }
    val launched = launch(run, inputText(run, fixSet))
    val changed = dirtyPaths(run)
    return when {
      launched.isNullOrBlank() || changed == null ->
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

  private fun repairReadinessProblem(run: FixRun): String? =
    branchProblem(run) ?: if (!matchesAssessedHead(run)) "the repository head differs from the assessed round" else null

  private fun launch(
    run: FixRun,
    text: String,
    definition: SkeletonDefinition = SkeletonDefinition.GOAL_ASSESSMENT_FIX,
  ): String? {
    val phaseRequest =
      services.phaseRequest(
        run.state,
        run.request,
        definition,
        PhaseInstructions(
          text,
          setOf(
            if (definition == SkeletonDefinition.GOAL_ASSESSMENT) {
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT
            } else {
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT_FIX
            },
          ),
        ),
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
    val changed = dirtyPaths(run)
    val pathsMatch = changed != null && changed.toSet() == fix.changedFiles.toSet()
    if (!pathsMatch) services.warn("validation changed unexpected files or the worktree status is unavailable")
    return fix.copy(
      validation = if (passed && pathsMatch) GoalAssessmentFixValidation.PASSED else GoalAssessmentFixValidation.FAILED,
    )
  }

  private fun committed(
    run: FixRun,
    fix: GoalAssessmentFix,
  ): GoalAssessmentFix {
    val round = run.round()
    val head = (gitOperations.headCommitSha(run.request.repoRoot) as? WorkflowGitOperationResult.Ok)?.value?.trim()
    val branchProblem = branchProblem(run)
    return when {
      fix.validation != GoalAssessmentFixValidation.PASSED -> unrecorded(fix, "post-change validation did not pass")
      branchProblem != null -> unrecorded(fix, branchProblem)
      head.isNullOrEmpty() -> unrecorded(fix, "the head commit could not be read")
      !head.equals(round.headSha, ignoreCase = true) -> adopted(run, fix, round, head)
      else -> commitRound(run, fix)
    }
  }

  private fun commitRound(
    run: FixRun,
    fix: GoalAssessmentFix,
  ): GoalAssessmentFix {
    val paths = dirtyPaths(run) ?: return unrecorded(fix, "the worktree status could not be read")
    val pathProblem =
      when {
        paths.toSet() != fix.changedFiles.toSet() -> "worktree paths no longer match the recorded repair paths"
        paths.isEmpty() -> "the recorded repair paths are no longer present"
        else -> null
      }
    if (pathProblem != null) return unrecorded(fix, pathProblem)
    val staged = gitOperations.stagePaths(run.request.repoRoot, fix.changedFiles)
    if (staged !is WorkflowGitOperationResult.Ok) return unrecorded(fix, "staging failed: ${staged.error}")
    val subject = "${run.state.manifest.issueKey}: goal assessment round ${run.roundNumber}"
    return when (val commit = gitOperations.createCommit(run.request.repoRoot, subject)) {
      is WorkflowGitCommitResult.Committed -> fix.copy(commitSha = commit.commitSha.ifBlank { null })
      WorkflowGitCommitResult.NothingToCommit -> unrecorded(fix, "git found nothing to commit")
      is WorkflowGitCommitResult.Failed -> unrecorded(fix, "the commit failed: ${commit.error}")
    }
  }

  private fun adopted(
    run: FixRun,
    fix: GoalAssessmentFix,
    round: GoalAssessmentRound,
    head: String,
  ): GoalAssessmentFix {
    if (fix.validation != GoalAssessmentFixValidation.PASSED) {
      return unrecorded(fix, "a commit cannot be adopted without passed post-change validation")
    }
    branchProblem(run)?.let { return unrecorded(fix, it) }
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

  private fun matchesAssessedHead(run: FixRun): Boolean {
    val head = gitOperations.headCommitSha(run.request.repoRoot) as? WorkflowGitOperationResult.Ok ?: return false
    return head.value.trim().equals(run.round().headSha, ignoreCase = true)
  }

  private fun failed(
    run: FixRun,
    reason: String,
    changedFiles: List<String> = emptyList(),
  ): GoalAssessmentFix {
    services.warn("the goal assessment repair is unavailable: $reason")
    return run.save(
      GoalAssessmentFix(
        GoalAssessmentFixStatus.FAILED,
        changedFiles,
        null,
        GoalAssessmentFixValidation.NOT_RUN,
      ),
    )
  }

  private fun inputText(
    run: FixRun,
    fixSet: GoalAssessmentFixSet,
  ): String {
    val findings = run.record.rounds.flatMap { it.findings }.filter { it.findingRef in fixSet.findingRefs }
    val regressions =
      run.record.rounds.flatMap { it.regressions }.associateBy { it.regressionRef }.values
        .filter { it.regressionRef in fixSet.regressionRefs }
        .joinToString("\n") { "- ${it.regressionRef}: ${it.evidence}" }
    val scenarios =
      run.record.rounds.flatMap { it.scenarios }.associateBy { it.scenarioRef }.values
        .filter { it.scenarioRef in fixSet.scenarioRefs }.joinToString("\n") { "- ${it.scenarioRef}: ${it.evidence}" }
    val findingLines = findings.joinToString("\n", transform = ::findingText)
    return listOfNotNull(
      "# Goal assessment fix: ${run.state.manifest.issueKey}",
      run.round().repairPlan?.let { "## Accepted repair plan\n\n$it" },
      scenarios.takeIf(String::isNotBlank)?.let { "## Broken interaction scenarios\n\n$it" },
      findingLines.takeIf(String::isNotBlank)?.let { "## Findings to fix\n\n$it" },
      regressions.takeIf(String::isNotBlank)?.let { "## Transitional regressions to restore\n\n$it" },
    ).joinToString("\n\n")
  }

  private fun findingText(finding: GoalAssessmentFinding): String {
    val evidence = finding.verificationEvidence.takeIf(String::isNotBlank)?.let { " Evidence: $it" }.orEmpty()
    return "- ${finding.findingRef} kind ${finding.kind.wireValue} (${finding.severity.wireValue}), " +
      "test ${finding.path}:${finding.line}, guards ${finding.guardedRef ?: "none"}. Scenario: " +
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

    fun savePlan(plan: String) {
      val updated =
        record.copy(
          rounds = record.rounds.map { if (it.round == roundNumber) it.copy(repairPlan = plan) else it },
        )
      if (!services.persist(state, updated)) {
        throw SkillBillRuntimeException(
          GoalAssessmentFailureCode.PERSISTENCE,
          "The assessment repair plan could not be persisted.",
        )
      }
      record = updated
    }

    fun save(fix: GoalAssessmentFix): GoalAssessmentFix {
      val updated = record.copy(rounds = record.rounds.map { if (it.round == roundNumber) it.copy(fix = fix) else it })
      if (!services.persist(state, updated)) {
        throw SkillBillRuntimeException(
          GoalAssessmentFailureCode.PERSISTENCE,
          "The assessment repair outcome could not be persisted.",
        )
      }
      record = updated
      return fix
    }
  }

  private companion object {
    val NOT_FIXED = Regex("""(GA|TR|IS)-\d{3}: not fixed: .*""")
  }
}

internal enum class GoalAssessmentFailureCode : RuntimeFailureCode {
  PERSISTENCE,
}
