package skillbill.cli

import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.create
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.slot.statusProjectionPhaseStrategies
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliRunInputsRuntimeTest {
  @Test
  fun `omitted repository root resolves from the embedding invocation`() {
    val repoRoot = Files.createTempDirectory("skillbill-cli-config-injected-root")
    Files.createDirectories(repoRoot.resolve(".skill-bill"))
    Files.writeString(repoRoot.resolve(".skill-bill/config.yaml"), "spec_type: linear\n")

    val result =
      CliRuntime.run(
        listOf("config", "resolve-spec-type"),
        CliRuntimeContext(
          repositoryRoot = repoRoot,
          userHome = repoRoot,
          environment = emptyMap(),
        ),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("linear", result.stdout.trim())
  }

  @Test
  fun `relative explicit repository root resolves against the process directory`() {
    val embeddingRoot = Files.createTempDirectory("skillbill-cli-relative-embedding")
    val processRoot = Path.of("").toAbsolutePath().normalize()
    val relativeRoot = processRoot.resolve("build/skillbill-cli-relative-root-${System.nanoTime()}")
    Files.createDirectories(relativeRoot.resolve(".skill-bill"))
    Files.writeString(relativeRoot.resolve(".skill-bill/config.yaml"), "spec_type: linear\n")

    try {
      val relativePath = processRoot.relativize(relativeRoot).toString()
      val result =
        CliRuntime.run(
          listOf("config", "resolve-spec-type", "--repo-root", relativePath),
          CliRuntimeContext(
            repositoryRoot = embeddingRoot,
            userHome = embeddingRoot,
            environment = emptyMap(),
          ),
        )

      assertEquals(0, result.exitCode, result.stdout)
      assertEquals("linear", result.stdout.trim())
    } finally {
      relativeRoot.toFile().deleteRecursively()
    }
  }

  @Test
  fun `--home beats the embedding context for an adapter-resolved path in both option forms`() {
    val contextHome = Files.createTempDirectory("skillbill-cli-context-home")
    val selectedHome = Files.createTempDirectory("skillbill-cli-selected-home")
    val expected = selectedHome.resolve(".skill-bill/review-metrics.db").toAbsolutePath().normalize().toString()

    val spaced = goalStats(listOf("--home", selectedHome.toString()), contextHome)
    val joined = goalStats(listOf("--home=$selectedHome"), contextHome)

    assertEquals(0, spaced.exitCode, spaced.stdout)
    assertEquals(expected, decodeJsonObject(spaced.stdout)["db_path"])
    assertEquals(0, joined.exitCode, joined.stdout)
    assertEquals(expected, decodeJsonObject(joined.stdout)["db_path"])
    assertFalse(Files.exists(contextHome.resolve(".skill-bill")))
  }

  @Test
  fun `commands resolve the injected repository root, not the process directory`() {
    val repoRoot = Files.createTempDirectory("skillbill-cli-injected-root")
    Files.createDirectories(repoRoot.resolve(".git"))
    val realRoot = repoRoot.toRealPath()
    val specsRoot = realRoot.resolve(".feature-specs").toString()
    val featureTask = listOf("feature-task", "SKILL-901")

    val injectedRoot = CliRuntime.run(featureTask, injectedRootContext(repoRoot))
    val explicitRoot =
      CliRuntime.run(
        featureTask + listOf("--repo-root", realRoot.toString()),
        injectedRootContext(repoRoot),
      )
    val processRoot =
      CliRuntime.run(
        featureTask,
        CliRuntimeContext(environment = emptyMap(), userHome = repoRoot),
      )

    val injectedRootOutput = injectedRoot.stdout + injectedRoot.stderr
    val explicitRootOutput = explicitRoot.stdout + explicitRoot.stderr
    assertTrue(injectedRootOutput.contains(specsRoot), injectedRootOutput)
    assertTrue(explicitRootOutput.contains(specsRoot), explicitRootOutput)
    assertEquals(injectedRoot.exitCode, processRoot.exitCode, processRoot.stderr)
    val processRootOutput = processRoot.stdout + processRoot.stderr
    assertFalse(processRootOutput.contains(specsRoot), processRootOutput)

    val scaffold =
      CliRuntime.run(
        listOf("new", "--dry-run", "--format", "json"),
        injectedRootContext(repoRoot).copy(
          stdinText = "horizontal\nbill-injected-root-skill\nInjected root skill.\n",
        ),
      )

    assertEquals(0, scaffold.exitCode, scaffold.stdout)
    assertEquals(
      realRoot.resolve("skills/bill-injected-root-skill").toString(),
      decodeJsonObject(scaffold.stdout)["skill_path"],
    )
  }

  @Test
  fun `feature-task lookup uses the enclosing Git root identity for a child invocation`() {
    val repositoryRoot = Files.createTempDirectory("skillbill-cli-lookup-repository")
    Files.createDirectories(repositoryRoot.resolve(".git"))
    val invocationRoot = Files.createDirectories(repositoryRoot.resolve("nested/invocation"))
    val home = Files.createTempDirectory("skillbill-cli-lookup-home")
    val db = home.resolve("metrics.db")
    val context =
      CliRuntimeContext(
        dbPathOverride = db.toString(),
        repositoryRoot = invocationRoot,
        userHome = home,
        environment = emptyMap(),
      )
    val identity = CanonicalRepositoryRoot.repositoryIdentity(invocationRoot)
    val component = RuntimeComponent::class.create(context.toRuntimeContext(dbPathOverride = db.toString()))
    val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()
    val plan =
      statusProjectionPhaseStrategies().executionPlan(
        PhaseStrategySelectionFacts(SkeletonDefinition.STANDALONE, setOf(CodeReviewExecutionMode.DEFAULT)),
      )
    val descriptor =
      FeatureTaskRuntimeExecutionPlanCodec(validator).encodeExecution(
        plan,
        EffectiveGatePolicyInputs(
          ValidationGateCommandFamily.VALIDATION,
          null,
          null,
          null,
          ValidationDepth.DEFAULT,
          null,
        ),
      )
    val service = component.workflowService
    assertIs<WorkflowOpenResult.Ok>(
      service.open(
        WorkflowServiceOpenArgs(
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          issueKey = "SKILL-902",
          repositoryIdentity = identity,
          governedSpecPath = ".feature-specs/SKILL-902/spec.md",
          routeScope = FeatureTaskRouteScope.STANDALONE,
          executionPlan = ValidatedFeatureTaskRuntimeExecutionPlan.read(descriptor, validator),
        ),
      ),
    )

    val lookup = CliRuntime.run(listOf("feature-task", "lookup", "SKILL-902"), context)

    assertEquals(0, lookup.exitCode, lookup.stderr)
    assertContains(lookup.stdout, "already_running")
    assertContains(lookup.stdout, ".feature-specs/SKILL-902/spec.md")
    assertEquals("repo-root-realpath-v1:${repositoryRoot.toRealPath()}", identity)
  }

  @Test
  fun `db path override reaches the session factory from the context alone and from the flag alone`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-run-inputs-db")
    val dbPath = tempDir.resolve("metrics.db")
    val expected = dbPath.toAbsolutePath().normalize().toString()

    val fromContext =
      CliRuntime.run(
        listOf("goal-stats", "--format", "json"),
        CliRuntimeContext(dbPathOverride = dbPath.toString(), environment = emptyMap()),
      )
    val fromFlag =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "goal-stats", "--format", "json"),
        CliRuntimeContext(environment = emptyMap()),
      )

    assertEquals(0, fromContext.exitCode, fromContext.stdout)
    assertEquals(expected, decodeJsonObject(fromContext.stdout)["db_path"])
    assertEquals(0, fromFlag.exitCode, fromFlag.stdout)
    assertEquals(expected, decodeJsonObject(fromFlag.stdout)["db_path"])
  }

  @Test
  fun `uninstall previews the desktop layout reported by the host platform port`() {
    val home = Files.createTempDirectory("skillbill-cli-host-platform")

    val result =
      CliRuntime.run(
        listOf("--home", home.toString(), "uninstall", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          environment = emptyMap(),
          userHome = home,
          hostPlatformPort = StubHostPlatformPort("Windows 11"),
        ),
      )

    assertEquals(0, result.exitCode, result.stdout)
    val desktop = decodeJsonObject(result.stdout)["desktop"] as Map<*, *>
    assertEquals(null, desktop["launcher"])
    assertEquals(listOf(home.resolve(".local/bin/skillbill-desktop.cmd").toString()), desktop["files"])
    assertEquals(
      listOf(home.resolve("AppData/Local/SkillBill/Desktop/SkillBill").toString()),
      desktop["directories"],
    )
  }

  private fun injectedRootContext(repoRoot: Path) =
    CliRuntimeContext(
      environment = emptyMap(),
      userHome = repoRoot,
      repositoryRoot = repoRoot,
    )

  private fun goalStats(
    rootFlags: List<String>,
    contextHome: Path,
  ) = CliRuntime.run(
    rootFlags + listOf("goal-stats", "--format", "json"),
    CliRuntimeContext(environment = emptyMap(), userHome = contextHome),
  )
}

private class StubHostPlatformPort(override val osName: String) : HostPlatformPort {
  override fun resolveUserHome(): Path = Path.of("/tmp")

  override fun resolveEnvironment(): Map<String, String> = emptyMap()

  override fun resolveJavaHome(): Path = Path.of("/java")

  override fun resolveWorkingDirectory(): Path = Path.of("/workspace")

  override fun resolveTemporaryDirectory(): Path = Path.of("/tmp")

  override val jvmClassPath: String = ""
  override val javaCommand: String? = null
  override val pathSeparator: String = ":"
}
