package skillbill.engine.featuretask.phaserun

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.error.featuretask.PhaseIntakeRequiredError
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PhaseRunIntakeResolverTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-phase-intake-repo")
  private val database = FakeDatabaseSessionFactory(InMemoryWorkflowStates())
  private val resolver = testPhaseRunIntakeResolver(database)

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
  }

  @Test
  fun `a plan intake that is only an issue URL takes the key from the URL path`() {
    val intake = resolvePlan("https://linear.app/capmo/issue/ENG-1251/android-usercomponent-used-outside-a-session")

    assertEquals("ENG-1251", intake.issueKey)
  }

  @Test
  fun `an issue URL key is uppercased and ignores the query and fragment`() {
    val intake = resolvePlan("https://tracker.example.com/browse/skill-42?focus=comments#c-7 tighten the gate")

    assertEquals("SKILL-42", intake.issueKey)
  }

  @Test
  fun `a bare issue key wins over a key in a URL`() {
    val intake = resolvePlan("https://linear.app/capmo/issue/ENG-1251/slug SKILL-7")

    assertEquals("SKILL-7", intake.issueKey)
  }

  @Test
  fun `a URL whose path names no issue key still requires an intake key`() {
    assertFailsWith<PhaseIntakeRequiredError> { resolvePlan("https://github.com/org/repo/pulls describe the work") }
  }

  @Test
  fun `the URL host is never read as an issue key`() {
    assertFailsWith<PhaseIntakeRequiredError> { resolvePlan("https://abc-123/some/path") }
  }

  @Test
  fun `rejected spec invariants leave intake on its current fallback`() {
    val source =
      object : FeatureTaskRuntimeRunInvariantsSource {
        override fun read(specPath: Path) = FeatureTaskRuntimeRunInvariantsRead.Rejected("spec token is unreadable")
      }
    val intake =
      testPhaseRunIntakeResolver(database, source).resolve(
        SkeletonDefinition.PLAN,
        PhaseRunRequest(
          definitionId = SkeletonDefinition.PLAN.id,
          repoRoot = repoRoot,
          invokedAgentId = "claude",
          intake = ".feature-specs/SKILL-42-example/spec.md",
        ),
        currentBranch = null,
      )

    assertEquals("SKILL-42", intake.issueKey)
    assertEquals(listOf(".feature-specs/SKILL-42-example/spec.md"), intake.runInvariants.acceptanceCriteria)
  }

  private fun resolvePlan(intake: String): PhaseRunIntake =
    resolver.resolve(
      SkeletonDefinition.PLAN,
      PhaseRunRequest(
        definitionId = SkeletonDefinition.PLAN.id,
        repoRoot = repoRoot,
        invokedAgentId = "claude",
        intake = intake,
      ),
      currentBranch = null,
    )
}
