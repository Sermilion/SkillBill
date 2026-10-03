package skillbill.engine.featuretask.slot.pullrequest

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.phaserun.phaseRunEntry
import skillbill.engine.featuretask.runner.BranchSetupTestConfig
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.phasePerAgentAssignment
import skillbill.engine.featuretask.runner.satisfiedAuditLauncher
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.engine.featuretask.validation.passed
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

internal const val PR_STEP = "pr"
internal const val PR_SPEC_REFERENCE = ".feature-specs/SKILL-380-phase-slot-strategies/spec.md"
internal const val PR_FEATURE_BRANCH = "feat/SKILL-380-pr-rules"

internal data class StandalonePrRun(
  val report: FeatureTaskRuntimeRunReport,
  val launcher: RuntimeRecordingLauncher,
) {
  val prPrompts: List<String> get() = launcher.promptsFor(PR_STEP)
}

internal data class PhasePrRun(
  val result: PhaseRunResult,
  val launcher: RuntimeRecordingLauncher,
) {
  val prPrompts: List<String> get() = launcher.promptsFor(PR_STEP)
}

internal fun standalonePrRun(
  repoRoot: Path,
  database: DatabaseSessionFactory,
  lookup: PullRequestIdentityLookup,
  git: RecordingWorkflowGitOperations = standaloneGit(),
): StandalonePrRun {
  val launcher = satisfiedAuditLauncher()
  val config =
    RuntimeHarnessConfig(
      branchSetup = BranchSetupTestConfig(gitOperations = git, specReference = PR_SPEC_REFERENCE),
      repoRoot = repoRoot,
      agentAssignment = phasePerAgentAssignment(),
      validationGatePlatformManifests = listOf(kotlinPackWithValidationGate()),
      validationGateRunner =
        object : ValidationGateRunner {
          override fun run(request: ValidationGateRunRequest) = passed()
        },
      launcher = launcher,
      pullRequestIdentityLookup = lookup,
    )
  val harness =
    telemetryRunnerHarness(
      launcher = launcher,
      runtimeConfig = config,
      databaseFactory = { database },
    )
  return StandalonePrRun(harness.runner.run(harness.request), launcher)
}

internal fun standaloneGit(): RecordingWorkflowGitOperations =
  committedRepoBranchSetup().gitOperations.also { it.currentBranchValue = PR_FEATURE_BRANCH }

internal fun phasePrRun(
  repoRoot: Path,
  database: DatabaseSessionFactory,
  lookup: PullRequestIdentityLookup,
  clock: Clock,
): PhasePrRun {
  val launcher =
    RuntimeRecordingLauncher { request ->
      facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
    }
  val config =
    RuntimeHarnessConfig(
      repoRoot = repoRoot,
      launcher = launcher,
      pullRequestIdentityLookup = lookup,
      gitOperationsOverride = GitWorkflowGitOperations(),
    )
  val harness =
    telemetryRunnerHarness(
      runtimeConfig = config,
      databaseFactory = { database },
    )
  val request = PhaseRunRequest(definitionId = SkeletonDefinition.PR.id, repoRoot = repoRoot, invokedAgentId = "claude")
  return PhasePrRun(
    phaseRunEntry(harness.strategies, config.harnessGitOperations, database, clock, harness.runLoopEntry).run(request),
    launcher,
  )
}

internal class PrRunRepository(private val root: Path) {
  private val origin: Path = root.resolve("origin.git")
  val repoRoot: Path = root.resolve("repo")

  fun initOnFeatureBranch() {
    git(root, "init", "--bare", "--initial-branch=$MAIN", origin.toString())
    git(root, "init", "--initial-branch=$MAIN", repoRoot.toString())
    git(repoRoot, "config", "user.email", "pr-rules@example.com")
    git(repoRoot, "config", "user.name", "PR Rules")
    git(repoRoot, "config", "commit.gpgsign", "false")
    git(repoRoot, "remote", "add", "origin", origin.toString())
    commit("README.md")
    git(repoRoot, "push", "-u", "origin", MAIN)
    git(repoRoot, "checkout", "-b", PR_FEATURE_BRANCH)
  }

  fun commit(vararg relatives: String) {
    relatives.forEach { relative -> write(relative, "content of $relative\n") }
    git(repoRoot, "add", "-A")
    git(repoRoot, "commit", "-m", "Change ${relatives.joinToString()}")
  }

  fun write(
    relative: String,
    content: String,
  ) {
    val target = repoRoot.resolve(relative)
    Files.createDirectories(target.parent)
    Files.writeString(target, content)
  }

  fun git(
    workDir: Path,
    vararg args: String,
  ): String {
    val process =
      ProcessBuilder(listOf("git", "-C", workDir.toString()) + args.toList())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }

  private companion object {
    const val MAIN = "main"
  }
}

private fun RuntimeRecordingLauncher.promptsFor(phaseId: String): List<String> =
  requests
    .map { request -> requireNotNull(request.skillRunRequest.promptOverride) }
    .filter { prompt -> phaseIdFromPrompt(prompt) == phaseId }
