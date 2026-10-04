package skillbill.cli.featuretask

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FeatureTaskRuntimePreparationTest {
  @Test
  fun `invalid operator and conflicting review options fail before creating a workflow`() {
    val home = Files.createTempDirectory("skillbill-feature-task-preparation")
    val db = home.resolve("metrics.db")
    val context = CliRuntimeContext(userHome = home, environment = emptyMap())
    val invocations =
      listOf(
        listOf(
          "--db",
          db.toString(),
          "feature-task",
          "SKILL-348",
          "--operator-decision",
          "unknown",
        ),
        listOf(
          "--db",
          db.toString(),
          "feature-task",
          "SKILL-348",
          "--code-review-mode",
          "inline",
          "--code-review-mode",
          "auto",
        ),
      )

    invocations.forEach { arguments ->
      val result = CliRuntime.run(arguments, context)

      assertEquals(1, result.exitCode, result.stderr)
      assertContains(result.stderr, "Error:")
    }

    val database =
      sqliteDatabaseSessionFactory(userHome = home, dbPathOverride = db.toString(), environment = emptyMap())
    database.transaction { unitOfWork ->
      assertEquals(emptyList(), unitOfWork.workflowStates.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME))
    }
  }

  @Test
  fun `unknown code review mode fails with the usage text`() {
    val home = Files.createTempDirectory("skillbill-feature-task-preparation")
    val db = home.resolve("metrics.db")
    val repo = Files.createDirectories(home.resolve("repo"))
    Files.createDirectories(repo.resolve(".git"))
    val spec = repo.resolve(".feature-specs/SKILL-348-review-mode/spec.md")
    Files.createDirectories(spec.parent)
    Files.writeString(spec, "# Spec\n")
    val context = CliRuntimeContext(userHome = home, environment = mapOf("SKILL_BILL_TEST_ENVIRONMENT" to "present"))

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          db.toString(),
          "feature-task",
          "run",
          "SKILL-348",
          spec.toString(),
          "--repo-root",
          repo.toString(),
          "--code-review-mode",
          "delegated",
        ),
        context,
      )

    assertEquals(1, result.exitCode, result.stderr)
    assertContains(result.stderr, "Unknown code-review execution mode 'delegated'. Allowed: auto, inline.")
  }

  @Test
  fun `run with a spec outside the governed directory fails with the CLI usage text before opening a workflow`() {
    val home = Files.createTempDirectory("skillbill-feature-task-preparation")
    val db = home.resolve("metrics.db")
    val repo = Files.createDirectories(home.resolve("repo"))
    Files.createDirectories(repo.resolve(".git"))
    val spec = repo.resolve("docs/spec.md")
    Files.createDirectories(spec.parent)
    Files.writeString(spec, "# Spec\n")
    val environment = mapOf("SKILL_BILL_TEST_ENVIRONMENT" to "present")

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          db.toString(),
          "feature-task",
          "run",
          "SKILL-392",
          spec.toString(),
          "--repo-root",
          repo.toString(),
          "--agent",
          "claude",
        ),
        CliRuntimeContext(
          userHome = home,
          environment = environment,
          executableLookup = ExecutableLookup { true },
        ),
      )

    assertEquals(1, result.exitCode, result.stderr)
    assertContains(result.stderr, "Governed spec path must be Markdown beneath .feature-specs/.")
    val database =
      sqliteDatabaseSessionFactory(userHome = home, dbPathOverride = db.toString(), environment = environment)
    database.transaction { unitOfWork ->
      assertEquals(emptyList(), unitOfWork.workflowStates.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME))
    }
  }
}
