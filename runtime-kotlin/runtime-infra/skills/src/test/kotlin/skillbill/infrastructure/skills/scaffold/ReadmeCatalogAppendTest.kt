package skillbill.infrastructure.skills.scaffold

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ScaffoldFailureCode
import skillbill.infrastructure.skills.scaffold.manifest.appendReadmeCatalogRow

class ReadmeCatalogAppendTest {
  private val readmeWithCatalog =
    """
    # Skill Bill

    ## Slash commands

    | Skill | Purpose |
    |-------|---------|
    | `/bill-boundary-history` | Record reusable feature history |
    | `/bill-code-check` | Stable quality-check entry point |
    | `/bill-code-review` | Stable code-review entry point |
    | `/bill-pr-description` | Generate a PR title and description |

    Trailing content.
    """.trimIndent() + "\n"

  @Test
  fun `appendReadmeCatalogRow inserts row in alphabetical position`(
    @TempDir tempDir: Path,
  ) {
    val readme = tempDir.resolve("README.md")
    readme.toFile().writeText(readmeWithCatalog)

    appendReadmeCatalogRow(readme, "bill-pr-review-fix", "Resolve PR review comments end-to-end")

    val updated = readme.toFile().readText()
    val lines = updated.lines()
    val skillRows = lines.filter { it.startsWith("| `/bill-") }
    assertEquals(
      listOf(
        "| `/bill-boundary-history` | Record reusable feature history |",
        "| `/bill-code-check` | Stable quality-check entry point |",
        "| `/bill-code-review` | Stable code-review entry point |",
        "| `/bill-pr-description` | Generate a PR title and description |",
        "| `/bill-pr-review-fix` | Resolve PR review comments end-to-end |",
      ),
      skillRows,
      "New row must land in alphabetical order after bill-pr-description.",
    )
  }

  @Test
  fun `appendReadmeCatalogRow is idempotent when the skill already exists`(
    @TempDir tempDir: Path,
  ) {
    val readme = tempDir.resolve("README.md")
    readme.toFile().writeText(readmeWithCatalog)

    appendReadmeCatalogRow(readme, "bill-pr-description", "Different description")

    val updated = readme.toFile().readText()
    assertEquals(
      readmeWithCatalog,
      updated,
      "Re-adding an existing row must leave the README untouched (no description rewrite).",
    )
  }

  @Test
  fun `appendReadmeCatalogRow appends after the last row when the new name sorts after all`(
    @TempDir tempDir: Path,
  ) {
    val readme = tempDir.resolve("README.md")
    readme.toFile().writeText(readmeWithCatalog)

    appendReadmeCatalogRow(readme, "bill-zzz-final", "Sorts after all others")

    val updated = readme.toFile().readText()
    val skillRows = updated.lines().filter { it.startsWith("| `/bill-") }
    assertEquals("| `/bill-zzz-final` | Sorts after all others |", skillRows.last())
  }

  @Test
  fun `appendReadmeCatalogRow throws when no catalog rows exist in the file`(
    @TempDir tempDir: Path,
  ) {
    val readme = tempDir.resolve("README.md")
    readme.toFile().writeText("# Skill Bill\n\nNo table here.\n")

    val error =
      assertThrows(SkillBillRuntimeException::class.java) {
        appendReadmeCatalogRow(readme, "bill-foo", "desc")
      }
    assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, error.code)
    assertTrue(error.message!!.contains("README.md does not contain a `/bill-*` catalog table"))
  }

  @Test
  fun `appendReadmeCatalogRow sanitizes pipe and whitespace in description`(
    @TempDir tempDir: Path,
  ) {
    val readme = tempDir.resolve("README.md")
    readme.toFile().writeText(readmeWithCatalog)

    appendReadmeCatalogRow(
      readme,
      "bill-aaa-first",
      "Multi-line\n  description with | a pipe character",
    )

    val updated = readme.toFile().readText()
    val firstSkillRow = updated.lines().first { it.startsWith("| `/bill-") }
    assertEquals(
      "| `/bill-aaa-first` | Multi-line description with \\| a pipe character |",
      firstSkillRow,
    )
  }

  @Test
  fun `appendReadmeCatalogRow falls back to a TODO description when input is blank`(
    @TempDir tempDir: Path,
  ) {
    val readme = tempDir.resolve("README.md")
    readme.toFile().writeText(readmeWithCatalog)

    appendReadmeCatalogRow(readme, "bill-aaa-todo", "   ")

    val updated = readme.toFile().readText()
    assertTrue(
      updated.contains("| `/bill-aaa-todo` | TODO: describe this skill. |"),
      "Blank descriptions must surface a clear placeholder rather than an empty cell.",
    )
  }
}
