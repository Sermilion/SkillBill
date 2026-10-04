package skillbill.infrastructure.workflow.goalplanning

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import skillbill.contracts.time.JvmSystemClock
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryBodyResolutionCaps
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryHeading
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileSystemGoalPlanningVerificationBodyResolverTest {
  @Test
  fun `over budget verification resolution raises the named cap error`() {
    val repo = Files.createTempDirectory("goal-verification-body-cap")
    val agent = Files.createDirectories(repo.resolve("modules/a/agent"))
    val headings =
      (0 until GoalPlanningContext.VERIFICATION_MAX_SELECTED_BODIES + 2).joinToString("\n\n") { index ->
        "## [${LocalDate.now(ZoneOffset.UTC).minusDays((index % 28).toLong())}] entry-$index\n\nbody $index"
      }
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n$headings\n")
    val catalog =
      FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discoverForFindingPaths(
        repo,
        listOf("modules/a/src/Main.kt"),
      ).boundaryCatalog
    val selected = catalog.map(GoalPlanningBoundaryHeading::headingId)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        FileSystemGoalPlanningBoundaryBodyResolver().resolve(
          repo,
          selected,
          catalog.map(GoalPlanningBoundaryHeading::headingId).toSet(),
          caps = GoalPlanningBoundaryBodyResolutionCaps.VERIFICATION,
          loudFailOnCapExceeded = true,
        )
      }.also { assertEquals(WorkflowFailureCode.GOAL_VERIFICATION_BOUNDARY_CAP_EXCEEDED, it.code) }
    assertEquals(
      "finding verification boundary body resolution exceeded max_selected_bodies or max_total_body_bytes",
      error.message,
    )
  }

  @Test
  fun `per body byte cap truncates the body under verification caps`() {
    val repo = Files.createTempDirectory("goal-verification-body-bytes")
    val agent = Files.createDirectories(repo.resolve("modules/a/agent"))
    val bigBody = "x".repeat(GoalPlanningContext.VERIFICATION_MAX_BODY_BYTES + 64)
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [${LocalDate.now(ZoneOffset.UTC)}] big-entry\n\n$bigBody\n",
    )
    val catalog = catalogOf(repo)
    val headingId = catalog.single().headingId

    val resolved =
      FileSystemGoalPlanningBoundaryBodyResolver().resolve(
        repo,
        listOf(headingId),
        catalog.map(GoalPlanningBoundaryHeading::headingId).toSet(),
        caps = GoalPlanningBoundaryBodyResolutionCaps.VERIFICATION,
        loudFailOnCapExceeded = true,
      )

    assertTrue(resolved.truncated)
    assertEquals(emptyList(), resolved.unresolvedHeadingIds)
    assertEquals(
      GoalPlanningContext.VERIFICATION_MAX_BODY_BYTES,
      resolved.bodies.single().body.toByteArray(Charsets.UTF_8).size,
    )
  }

  private fun catalogOf(repo: Path): List<GoalPlanningBoundaryHeading> =
    FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discoverForFindingPaths(
      repo,
      listOf("modules/a/src/Main.kt"),
    ).boundaryCatalog
}
