package skillbill.engine.featuretask.phaserun

import skillbill.error.featuretask.PhaseIntakeRequiredError
import skillbill.infrastructure.workflow.featuretask.FileSystemFeatureTaskRuntimeRunInvariantsSource
import skillbill.infrastructure.workflow.filesystem.FileSystemFeatureSpecPathResolver
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PhaseRunIntakeResolverTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-phase-intake-repo")
  private val resolver =
    PhaseRunIntakeResolver(FileSystemFeatureSpecPathResolver(), FileSystemFeatureTaskRuntimeRunInvariantsSource())

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
