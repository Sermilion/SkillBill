package skillbill.di.core

import skillbill.application.DesktopFixtureDatabaseWriter
import skillbill.application.DesktopFixtureWriteResult
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.model.EnvironmentContext
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.system.exitProcess

fun writeDesktopFixture(
  home: Path,
  checkoutRoot: Path,
  clock: Clock = Clock.systemUTC(),
  hostEnvironment: Map<String, String> = System.getenv(),
): DesktopFixtureWriteResult {
  val targetHome = validatedDesktopFixtureHome(home, checkoutRoot, hostEnvironment)
  val stateDirectory = targetHome.resolve(".skill-bill")
  if (Files.exists(targetHome)) {
    Files.walk(targetHome).use { paths ->
      paths.sorted(Comparator.reverseOrder<Path>()).forEach(Files::deleteIfExists)
    }
  }
  Files.createDirectories(stateDirectory)
  val databasePath = stateDirectory.resolve("review-metrics.db")
  val git = GitWorkflowGitOperations()
  val runtime =
    RuntimeComponent::class.create(
      RuntimeContext(
        environment =
          EnvironmentContext(
            dbPathOverride = databasePath.toString(),
            environment =
              mapOf(
                "SKILL_BILL_STATE_DIR" to stateDirectory.toString(),
                "SKILL_BILL_DB" to databasePath.toString(),
                "SKILL_BILL_TEST" to "1",
              ),
            userHome = targetHome,
            repositoryRoot = CanonicalRepositoryRoot.canonicalPath(checkoutRoot),
          ),
        transport = TransportContext(),
        workflowOps = WorkflowOpsContext(workflowGitOperations = git),
        callbacks = OptionalCallbacks(),
      ),
    )
  return try {
    val descriptor =
      runtime.featureTaskRuntimeExecutionPlanResolver.resolveCreation(
        FeatureTaskRuntimeExecutionPlanCreationRequest(
          repoRoot = CanonicalRepositoryRoot.canonicalPath(checkoutRoot),
          definition = SkeletonDefinition.GOAL_CHILD_REVISION_5,
          reviewMode = CodeReviewExecutionMode.INLINE,
          qualityGate = FeatureTaskRuntimeQualityGateSelection.VALIDATE,
          validationDepth = ValidationDepth.FULL,
          timeout = null,
        ),
      )
    val commitSha =
      when (val result = git.headCommitSha(CanonicalRepositoryRoot.canonicalPath(checkoutRoot))) {
        is WorkflowGitOperationResult.Ok -> result.value.trim().takeIf(String::isNotBlank)
        is WorkflowGitOperationResult.Failed -> null
      } ?: error("Unable to resolve a nonblank checkout HEAD for desktop fixture: ${checkoutRoot.toAbsolutePath()}")
    DesktopFixtureDatabaseWriter(runtime.databaseSession, runtime.projectStorePort, clock, checkoutRoot)
      .write(descriptor, commitSha)
      .also { runtime.ideStatusService.board(WorkBoardRequest(observedAt = clock.instant())) }
  } finally {
    (runtime as? AutoCloseable)?.close()
  }
}

fun main(args: Array<String>) {
  if (args.size != 2) {
    System.err.println("Usage: DesktopFixtureDbMain <target-home> <checkout-root>")
    exitProcess(2)
  }
  val home = Path.of(args[0]).toAbsolutePath().normalize()
  val result =
    try {
      writeDesktopFixture(home, Path.of(args[1]))
    } catch (error: IllegalArgumentException) {
      System.err.println(error.message)
      exitProcess(1)
    }
  val databasePath = home.resolve(".skill-bill/review-metrics.db")
  println("Desktop fixture database: $databasePath")
  println("Desktop fixture specs: ${result.specsRoot}")
}
