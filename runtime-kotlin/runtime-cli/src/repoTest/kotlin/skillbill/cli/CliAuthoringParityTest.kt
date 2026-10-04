package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.infrastructure.skills.scaffold.authoring.renderAuthoringTarget
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliAuthoringParityTest {
  @Test
  fun `native authoring inspection commands stay available through the kotlin cli`() {
    val repoRoot = outerRepoRoot()
    val tempDir = Files.createTempDirectory("skillbill-cli-authoring")
    val context = CliRuntimeContext(userHome = tempDir)

    val listed =
      runJson(
        listOf(
          "list",
          "--repo-root",
          repoRoot.toString(),
          "--skill-name",
          "skill-bill",
          "--format",
          "json",
        ),
        context,
      )
    val shown =
      runJson(
        listOf(
          "show",
          "skill-bill",
          "--repo-root",
          repoRoot.toString(),
          "--content",
          "none",
          "--format",
          "json",
        ),
        context,
      )
    val explained =
      runJson(
        listOf(
          "explain",
          "skill-bill",
          "--repo-root",
          repoRoot.toString(),
          "--format",
          "json",
        ),
        context,
      )

    assertEquals(1, listed["skill_count"])
    assertEquals("skill-bill", shown["skill_name"])
    assertEquals("skill-bill", (explained["skill"] as Map<*, *>)["skill_name"])
  }

  @Test
  fun `native authoring validation command stays available through the kotlin cli`() {
    val repoRoot = outerRepoRoot()
    val tempDir = Files.createTempDirectory("skillbill-cli-authoring-validation")
    val context = CliRuntimeContext(userHome = tempDir)
    val validated =
      runJson(
        listOf(
          "validate",
          "--repo-root",
          repoRoot.toString(),
          "--skill-name",
          "skill-bill",
          "--format",
          "json",
        ),
        context,
      )

    assertEquals("pass", validated["status"])
  }

  @Test
  fun `native authoring validation surfaces failure envelope with exit code 1`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-authoring-validation-fail")
    val context = CliRuntimeContext(userHome = tempDir)
    val skillName = "bill-validate-fail-fixture"
    val repoRoot = authoringFixtureRepo(tempDir.resolve("validate-fail-repo"), skillName)

    val contentFile = repoRoot.resolve("skills").resolve(skillName).resolve("content.md")
    Files.writeString(
      contentFile,
      Files.readString(contentFile).replace(Regex("(?m)^description:.*$"), "description:"),
    )

    val result =
      CliRuntime.run(
        listOf(
          "validate",
          "--repo-root",
          repoRoot.toString(),
          "--skill-name",
          skillName,
          "--format",
          "json",
        ),
        context,
      )
    val payload = decodeJsonObject(result.stdout)
    val issues = payload["issues"]

    assertEquals(1, result.exitCode, result.stdout)
    assertEquals("fail", payload["status"])
    assertEquals("selected", payload["mode"])
    assertTrue(issues is List<*> && issues.isNotEmpty(), "expected non-empty issues list, got $issues")
  }

  @Test
  fun `native render validation and content mutation commands stay available through the kotlin cli`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-authoring-mutation")
    val context = CliRuntimeContext(userHome = tempDir)

    assertWrapperCommandRegenerates("upgrade", tempDir, context)
    assertEditBodyFileUpdatesContent(tempDir, context)
    assertFillBodyUpdatesContent(tempDir, context)
  }

  @Test
  fun `edit section command targets authored sections and rejects generated wrapper sections`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-authoring-sections")
    val context = CliRuntimeContext(userHome = tempDir)

    assertEditSectionUpdatesAuthoredContent(tempDir, context)
    assertEditSectionRejectsGeneratedWrapperSection(tempDir, context)
  }

  @Test
  fun `render command emits read-only stdout and dry run matches normal output`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-authoring-render")
    val context = CliRuntimeContext(userHome = tempDir)
    val skillName = "bill-render-cli-fixture"
    val repoRoot = authoringFixtureRepo(tempDir.resolve("render-cli-repo"), skillName)
    val skillFile = repoRoot.resolve("skills").resolve(skillName).resolve("SKILL.md")
    val expected = renderAuthoringTarget(repoRoot, skillName).stdout

    val normal =
      CliRuntime.run(
        listOf(
          "render",
          skillName,
          "--repo-root",
          repoRoot.toString(),
        ),
        context,
      )
    val dryRun =
      CliRuntime.run(
        listOf(
          "render",
          "--dry-run",
          skillName,
          "--repo-root",
          repoRoot.toString(),
        ),
        context,
      )

    assertEquals(0, normal.exitCode, normal.stdout)
    assertEquals(0, dryRun.exitCode, dryRun.stdout)
    assertEquals(expected, normal.stdout)
    assertEquals(normal.stdout, dryRun.stdout)
    assertRenderSkillNameOption(normal.stdout, skillName, repoRoot, context)
    assertEquals(false, Files.exists(skillFile))
  }

  @Test
  fun `interactive authoring modes are retired with stable replacements`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-retired-authoring")
    val context = CliRuntimeContext(userHome = tempDir)

    listOf(
      listOf("new-addon", "--interactive", "--format", "json") to
        "new-addon --interactive interactive mode was retired in SKILL-32; " +
        "use `skill-bill new-addon --platform <platform> --name <name>` instead.",
      listOf("create-and-fill", "--interactive", "--format", "json") to
        "create-and-fill interactive mode was retired in SKILL-32; " +
        "use `skill-bill create-and-fill --payload <file> --body-file <file>` instead.",
      listOf("edit", "skill-bill", "--repo-root", outerRepoRoot().toString(), "--format", "json") to
        "edit interactive mode was retired in SKILL-32; " +
        "use `skill-bill fill skill-bill --body-file <file>` instead.",
      listOf(
        "edit",
        "skill-bill",
        "--repo-root",
        outerRepoRoot().toString(),
        "--editor",
        "--format",
        "json",
      ) to
        "edit --editor editor mode was retired in SKILL-32; " +
        "use `skill-bill fill skill-bill --body-file <file>` instead.",
    ).forEach { (arguments, expectedError) ->
      val result = CliRuntime.run(arguments, context)
      val payload = decodeJsonObject(result.stdout)

      assertEquals(1, result.exitCode, result.stdout)
      assertEquals("unsupported", payload["status"])
      assertEquals(expectedError, payload["error"].toString())
    }
  }
}

private fun runJson(
  arguments: List<String>,
  context: CliRuntimeContext,
): Map<String, Any?> {
  val result = CliRuntime.run(arguments, context)
  assertEquals(0, result.exitCode, result.stdout)
  return decodeJsonObject(result.stdout)
}

private fun outerRepoRoot(): Path {
  var current = Path.of("").toAbsolutePath().normalize()
  while (current.parent != null) {
    val hasSettings = Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts"))
    val hasSkills = Files.isDirectory(current.resolve("skills"))
    if (hasSettings && hasSkills) {
      return current
    }
    current = current.parent
  }
  error("Could not locate skill-bill repository root from ${Path.of("").toAbsolutePath().normalize()}")
}

private fun authoringFixtureRepo(
  repoRoot: Path,
  skillName: String,
): Path {
  val skillDir = repoRoot.resolve("skills").resolve(skillName)
  Files.createDirectories(skillDir)
  Files.writeString(
    skillDir.resolve("content.md"),
    """
    ---
    name: $skillName
    description: Fixture skill for CLI authoring tests.
    ---

    # Fixture Content

    Initial authored content.
    """.trimIndent() + "\n",
  )
  return repoRoot
}

private fun assertWrapperCommandRegenerates(
  command: String,
  tempDir: Path,
  context: CliRuntimeContext,
) {
  val skillName = "bill-$command-fixture"
  val repoRoot = authoringFixtureRepo(tempDir.resolve("$command-repo"), skillName)
  val payload =
    runJson(
      listOf(
        command,
        "--repo-root",
        repoRoot.toString(),
        "--skill-name",
        skillName,
        "--format",
        "json",
      ),
      context,
    )

  assertEquals(0, payload["regenerated_count"])
  assertEquals(true, payload["validator_ran"])
}

private fun assertRenderSkillNameOption(
  expectedOutput: String,
  skillName: String,
  repoRoot: Path,
  context: CliRuntimeContext,
) {
  val optionForm =
    CliRuntime.run(
      listOf(
        "render",
        "--skill-name",
        skillName,
        "--repo-root",
        repoRoot.toString(),
      ),
      context,
    )
  val duplicateInput =
    CliRuntime.run(
      listOf(
        "render",
        skillName,
        "--skill-name",
        skillName,
        "--repo-root",
        repoRoot.toString(),
      ),
      context,
    )

  assertEquals(0, optionForm.exitCode, optionForm.stdout)
  assertEquals(1, duplicateInput.exitCode, duplicateInput.stdout)
  assertEquals(expectedOutput, optionForm.stdout)
  assertContains(duplicateInput.stderr, "either as an argument or with --skill-name, not both")
}

private fun assertEditBodyFileUpdatesContent(
  tempDir: Path,
  context: CliRuntimeContext,
) {
  val repoRoot = authoringFixtureRepo(tempDir.resolve("edit-repo"), "bill-edit-fixture")
  val bodyFile = tempDir.resolve("edit-body.md")
  Files.writeString(bodyFile, "Edited body from a file.")
  val payload =
    runJson(
      listOf(
        "edit",
        "bill-edit-fixture",
        "--repo-root",
        repoRoot.toString(),
        "--body-file",
        bodyFile.toString(),
        "--format",
        "json",
      ),
      context,
    )

  assertEquals(false, payload["used_editor"])
  assertEquals("complete", payload["completion_status"])
  assertEquals(true, payload["validator_ran"])
}

private fun assertFillBodyUpdatesContent(
  tempDir: Path,
  context: CliRuntimeContext,
) {
  val repoRoot = authoringFixtureRepo(tempDir.resolve("fill-repo"), "bill-fill-fixture")
  val payload =
    runJson(
      listOf(
        "fill",
        "bill-fill-fixture",
        "--repo-root",
        repoRoot.toString(),
        "--body",
        "Filled body from inline CLI input.",
        "--format",
        "json",
      ),
      context,
    )

  assertEquals("complete", payload["completion_status"])
  assertEquals(true, payload["validator_ran"])
}

private fun assertEditSectionUpdatesAuthoredContent(
  tempDir: Path,
  context: CliRuntimeContext,
) {
  val repoRoot = sectionFixtureRepo(tempDir.resolve("edit-section-repo"), "bill-edit-section-fixture")
  val bodyFile = tempDir.resolve("edit-section-body.md")
  Files.writeString(bodyFile, "Focused replacement body.")

  val payload =
    runJson(
      listOf(
        "edit",
        "bill-edit-section-fixture",
        "--repo-root",
        repoRoot.toString(),
        "--body-file",
        bodyFile.toString(),
        "--section",
        "Review Guidance",
        "--format",
        "json",
      ),
      context,
    )
  val content = Files.readString(repoRoot.resolve("skills/bill-edit-section-fixture/content.md"))

  assertEquals("Review Guidance", payload["updated_section"])
  assertEquals(true, payload["validator_ran"])
  assertContains(content, "## Review Guidance\n\nFocused replacement body.")
  assertContains(content, "## Review Focus")
  assertContains(content, "Initial focus.")
}

private fun assertEditSectionRejectsGeneratedWrapperSection(
  tempDir: Path,
  context: CliRuntimeContext,
) {
  val repoRoot = sectionFixtureRepo(tempDir.resolve("edit-generated-section-repo"), "bill-edit-generated-fixture")
  val bodyFile = tempDir.resolve("edit-generated-section-body.md")
  Files.writeString(bodyFile, "Generated wrapper body.")
  val contentFile = repoRoot.resolve("skills/bill-edit-generated-fixture/content.md")
  val before = Files.readString(contentFile)

  val result =
    CliRuntime.run(
      listOf(
        "edit",
        "bill-edit-generated-fixture",
        "--repo-root",
        repoRoot.toString(),
        "--body-file",
        bodyFile.toString(),
        "--section",
        "Descriptor",
        "--format",
        "json",
      ),
      context,
    )
  val payload = decodeJsonObject(result.stdout)
  val message = payload["error"].toString()

  assertEquals(1, result.exitCode, result.stdout)
  assertEquals("error", payload["status"])
  assertContains(message, "Cannot edit generated wrapper section '## Descriptor'")
  assertContains(message, "Edit authored content.md sections")
  assertContains(message, "platform.yaml manifest fields")
  assertEquals(before, Files.readString(contentFile))
}

private fun sectionFixtureRepo(
  repoRoot: Path,
  skillName: String,
): Path {
  val skillDir = repoRoot.resolve("skills").resolve(skillName)
  Files.createDirectories(skillDir)
  Files.writeString(
    skillDir.resolve("content.md"),
    """
    ---
    name: $skillName
    description: Fixture skill for CLI section authoring tests.
    ---

    # Fixture Content

    ## Review Focus

    Initial focus.

    ## Review Guidance

    Initial guidance.
    """.trimIndent() + "\n",
  )
  return repoRoot
}
