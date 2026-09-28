package skillbill.infrastructure.contracts.workflow

import skillbill.goalrunner.planning.GoalPlanningExcludedPaths
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExcludedRootAgentTreeAbsenceTest {
  @Test
  fun `no working tree path under an excluded root contains an agent segment`() {
    val repoRoot = repoRootFromTest()

    val offenders =
      workingTreeDirectories(repoRoot).filter { path ->
        GoalPlanningExcludedPaths.isExcluded(path) && path.split("/").contains("agent")
      }

    assertEquals(emptyList(), offenders, "delete these agent/ trees; excluded roots carry no boundary memory")
  }

  @Test
  fun `boundary writer directives forbid agent trees under excluded roots`() {
    val repoRoot = repoRootFromTest()
    val directiveDir =
      "runtime-kotlin/runtime-engine/src/main/resources/skillbill/engine/featuretask/slot/writehistory"
    listOf("boundary-history-directive.md", "boundary-decisions-directive.md").forEach { fileName ->
      val path = "$directiveDir/$fileName"
      val content = Files.readString(repoRoot.resolve(path))
      assertTrue(
        content.contains("never create `agent/` under `platform-packs/`"),
        "$path must forbid agent/ under excluded roots",
      )
      assertTrue(
        content.contains("goal-planning discovery exclusion list"),
        "$path must name the exclusion list as the authority",
      )
    }
  }

  private fun workingTreeDirectories(repoRoot: Path): List<String> {
    val found = mutableListOf<String>()
    val pending = ArrayDeque(listOf(repoRoot))
    while (pending.isNotEmpty()) {
      val children =
        runCatching {
          Files.list(pending.removeFirst()).use { entries ->
            entries.filter { path -> Files.isDirectory(path) }.toList()
          }
        }.getOrDefault(emptyList())
      for (child in children) {
        if (child.fileName.toString() in GoalPlanningExcludedPaths.EXCLUDED_DIRECTORY_NAMES) continue
        found.add(repoRoot.relativize(child).joinToString("/"))
        pending.add(child)
      }
    }
    return found
  }
}
