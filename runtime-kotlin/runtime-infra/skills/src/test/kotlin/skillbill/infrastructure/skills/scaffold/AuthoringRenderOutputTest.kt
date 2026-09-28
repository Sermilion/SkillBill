package skillbill.infrastructure.skills.scaffold

import skillbill.infrastructure.skills.scaffold.authoring.renderAuthoringTarget
import skillbill.infrastructure.skills.scaffold.authoring.renderWrapper
import skillbill.infrastructure.skills.scaffold.authoring.resolveTarget
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.infrastructure.skills.scaffold.pointer.renderPointer
import skillbill.testsupport.SnapshotAssertions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AuthoringRenderOutputTest {
  private val tempRoot: Path = Files.createTempDirectory("skillbill-authoring-render-")

  @AfterTest
  fun cleanup() {
    if (Files.exists(tempRoot)) {
      Files.walk(tempRoot).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }

  @Test
  fun `renderAuthoringTarget emits deterministic wrapper stdout from renderWrapper without writing`() {
    val repoRoot = tempRoot.resolve("horizontal-repo")
    val skillDir = repoRoot.resolve("skills/bill-render-fixture")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: bill-render-fixture
      description: Fixture skill for render output.
      ---

      # Authored Body

      ## Review Steps

      Read the authored guidance from content.md.
      """.trimIndent() + "\n",
    )
    val target = resolveTarget(repoRoot, "bill-render-fixture")
    val renderedWrapper = renderWrapper(target)
    val expectedStdout =
      "===== SKILL.md: skills/bill-render-fixture/SKILL.md =====\n" +
        renderedWrapper.trimEnd('\r', '\n') +
        "\n"

    val first = renderAuthoringTarget(repoRoot, "bill-render-fixture")
    val second = renderAuthoringTarget(repoRoot, "bill-render-fixture")

    assertContains(renderedWrapper, "## Descriptor\n\nGoverned skill: `bill-render-fixture`")
    assertContains(renderedWrapper, "## Execution\n\n### Review Steps\n\nRead the authored guidance from content.md.")

    assertContains(renderedWrapper, "## Ceremony\n")
    assertFalse("Follow the instructions in [content.md](content.md)." in renderedWrapper)
    assertEquals(expectedStdout, first.stdout)
    assertEquals(first.stdout, second.stdout)
    assertFalse('\r' in first.stdout, "render stdout must use LF line endings only")
    assertFalse(Files.exists(skillDir.resolve("SKILL.md")), "render must not create source SKILL.md")
  }

  @Test
  fun `renderWrapper quotes frontmatter descriptions that are not valid plain yaml scalars`() {
    val repoRoot = tempRoot.resolve("quoted-frontmatter-repo")
    val skillDir = repoRoot.resolve("skills/bill-render-quoted")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: bill-render-quoted
      description: "Use as entry point: prepare the thing."
      ---

      # Authored Body

      Run the authored guidance.
      """.trimIndent() + "\n",
    )
    val renderedWrapper = renderWrapper(resolveTarget(repoRoot, "bill-render-quoted"))

    assertContains(renderedWrapper, "description: \"Use as entry point: prepare the thing.\"")
  }

  @Test
  fun `legacy bill-feature consumer delivers no pointer to a skill named bill-feature`() {
    val repoRoot = tempRoot.resolve("agent-addon-render-repo")
    val featureDir = repoRoot.resolve("skills/bill-feature")
    Files.createDirectories(featureDir)
    Files.writeString(
      featureDir.resolve("content.md"),
      "---\nname: bill-feature\ndescription: Feature router.\n---\n\nRun feature work.\n",
    )
    val addonDir = repoRoot.resolve("agent-addons/review-helper")
    Files.createDirectories(addonDir)
    Files.writeString(
      addonDir.resolve("agent-addon.yaml"),
      """
      contract_version: "1.0"
      slug: review-helper
      description: Review helper
      agent_ids:
        - codex
      consumers:
        - bill-feature
      """.trimIndent() + "\n",
    )
    Files.writeString(addonDir.resolve("content.md"), "Use the helper.\n")

    val render = renderAuthoringTarget(repoRoot, "bill-feature")

    assertEquals(
      listOf("===== SKILL.md: skills/bill-feature/SKILL.md ====="),
      render.blocks.map { block -> block.header },
    )
  }

  @Test
  fun `skill-bill render includes agent addon pointers while its sidecars render none`() {
    val repoRoot = tempRoot.resolve("skill-bill-addon-render-repo")
    val parentDir = repoRoot.resolve("skills/skill-bill")
    val sidecarDir = repoRoot.resolve("skills/skill-bill-review-helper")
    Files.createDirectories(parentDir)
    Files.createDirectories(sidecarDir)
    Files.writeString(
      parentDir.resolve("content.md"),
      "---\nname: skill-bill\ndescription: Skill Bill router.\n---\n\nRoute governed work.\n",
    )
    Files.writeString(
      sidecarDir.resolve("content.md"),
      "---\nname: skill-bill-review-helper\ninternal-for: skill-bill\n" +
        "description: Review sidecar.\n---\n\nRun review helper work.\n",
    )
    val addonDir = repoRoot.resolve("agent-addons/router-helper")
    Files.createDirectories(addonDir)
    Files.writeString(
      addonDir.resolve("agent-addon.yaml"),
      """
      contract_version: "1.0"
      slug: router-helper
      description: Router helper
      agent_ids:
        - codex
      consumers:
        - skill-bill
      """.trimIndent() + "\n",
    )
    Files.writeString(addonDir.resolve("content.md"), "Use the router helper.\n")

    val parentRender = renderAuthoringTarget(repoRoot, "skill-bill")
    val sidecarRender = renderAuthoringTarget(repoRoot, "skill-bill-review-helper")

    assertEquals(
      "===== pointer: skills/skill-bill/agent-addon-router-helper.md =====",
      parentRender.blocks.last().header,
    )
    assertEquals("Use the router helper.\n", parentRender.blocks.last().content)
    assertEquals(
      listOf("===== SKILL.md: skills/skill-bill-review-helper/SKILL.md ====="),
      sidecarRender.blocks.map { block -> block.header },
    )
  }

  @Test
  fun `platform-pack render includes pointer blocks from renderPointer in manifest declaration order`() {
    val fixture = writePlatformRenderFixture()

    val rendered = renderAuthoringTarget(fixture.repoRoot, "bill-fixturepack-code-review")
    val expectedZ =
      renderPointer(
        fixture.repoRoot,
        fixture.packRoot,
        loadPlatformManifest(fixture.packRoot).pointers[0],
      )
    val expectedA =
      renderPointer(
        fixture.repoRoot,
        fixture.packRoot,
        loadPlatformManifest(fixture.packRoot).pointers[1],
      )

    assertEquals(
      listOf(
        "===== SKILL.md: platform-packs/fixturepack/code-review/bill-fixturepack-code-review/SKILL.md =====",
        "===== pointer: platform-packs/fixturepack/code-review/bill-fixturepack-code-review/z.md =====",
        "===== pointer: platform-packs/fixturepack/code-review/bill-fixturepack-code-review/a.md =====",
      ),
      rendered.blocks.map { block -> block.header },
    )
    assertEquals(expectedZ, rendered.blocks[1].content)
    assertEquals(expectedA, rendered.blocks[2].content)
    assertFalse("## Review Composition" in rendered.stdout)
    SnapshotAssertions.assertMatchesSnapshot(
      "snapshots/scaffold/bill-fixturepack-code-review-no-composition.render.txt",
      rendered.stdout,
    )
    assertFalse(Files.exists(fixture.zPointer), "render must not create pointer files")
    assertFalse(Files.exists(fixture.aPointer), "render must not create pointer files")
    assertFalse(Files.exists(fixture.skillDir.resolve("SKILL.md")), "render must not create source SKILL.md")
  }

  private fun writePlatformRenderFixture(): PlatformRenderFixture {
    val repoRoot = tempRoot.resolve("platform-repo")
    val packRoot = repoRoot.resolve("platform-packs/fixturepack")
    val skillDir = packRoot.resolve("code-review/bill-fixturepack-code-review")
    Files.createDirectories(skillDir)
    Files.createDirectories(repoRoot.resolve("shared"))
    Files.writeString(repoRoot.resolve("shared/z.md"), "# z")
    Files.writeString(repoRoot.resolve("shared/a.md"), "# a")
    Files.writeString(skillDir.resolve("content.md"), platformFixtureContent())
    Files.writeString(packRoot.resolve("platform.yaml"), platformFixtureManifest())
    return PlatformRenderFixture(
      repoRoot = repoRoot,
      packRoot = packRoot,
      skillDir = skillDir,
      zPointer = skillDir.resolve("z.md"),
      aPointer = skillDir.resolve("a.md"),
    )
  }

  private fun platformFixtureContent(): String =
    """
    ---
    name: bill-fixturepack-code-review
    description: Fixture platform render skill.
    ---

    # Platform Body
    """.trimIndent() + "\n"

  private fun platformFixtureManifest(): String =
    """
    platform: fixturepack
    contract_version: "1.8"

    routing_signals:
      strong:
        - ".fixture"

    declared_code_review_areas: []

    declared_files:
      baseline: code-review/bill-fixturepack-code-review/content.md

    area_metadata: {}

    pointers:
      code-review/bill-fixturepack-code-review:
        - name: z.md
          target: shared/z.md
        - name: a.md
          target: shared/a.md
    """.trimIndent() + "\n"
}

private data class PlatformRenderFixture(
  val repoRoot: Path,
  val packRoot: Path,
  val skillDir: Path,
  val zPointer: Path,
  val aPointer: Path,
)
