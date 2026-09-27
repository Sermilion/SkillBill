package skillbill.engine.featuretask.slot.writehistory

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundaryMemoryRulesParityTest {
  private val skillsRoot: Path = locateSkillsRoot()

  @Test
  fun `the history entry format matches the boundary history skill`() {
    assertEquals(entryFormat("bill-boundary-history"), BoundaryMemoryPromptRules.HISTORY_ENTRY_FORMAT)
  }

  @Test
  fun `the decision entry format matches the boundary decisions skill`() {
    assertEquals(entryFormat("bill-boundary-decisions"), BoundaryMemoryPromptRules.DECISION_ENTRY_FORMAT)
  }

  @Test
  fun `the rules forbid memory under goal-planning excluded roots`() {
    val section = BoundaryMemoryPromptRules.section
    assertTrue("never create `agent/` under `platform-packs/`" in section, section)
    assertTrue("goal-planning discovery exclusion list" in section, section)
  }

  private fun entryFormat(skill: String): String {
    val content = Files.readString(skillsRoot.resolve("$skill/content.md"))
    val block = ENTRY_FORMAT_BLOCK.find(content) ?: error("$skill has no fenced Entry Format block")
    return block.groupValues[1].trimEnd()
  }

  private fun locateSkillsRoot(): Path =
    generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()) { it.parent }
      .map { candidate -> candidate.resolve("skills") }
      .firstOrNull { skills -> Files.isDirectory(skills.resolve("bill-boundary-history")) }
      ?: error("could not locate skills/bill-boundary-history above ${System.getProperty("user.dir")}")

  private companion object {
    val ENTRY_FORMAT_BLOCK = Regex("""## Entry Format\s*\n```markdown\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
  }
}
