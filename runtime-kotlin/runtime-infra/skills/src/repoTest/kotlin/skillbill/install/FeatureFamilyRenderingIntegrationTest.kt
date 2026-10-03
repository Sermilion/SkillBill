package skillbill.install

import skillbill.infrastructure.skills.install.staging.StageInstalledSkillInput
import skillbill.infrastructure.skills.install.staging.stageInstalledSkill
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.model.toPath
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureFamilyRenderingIntegrationTest {
  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir)) {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
      }
    }
  }

  @Test
  fun `governed feature family stages merged feature contracts without source mutation`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-feature-family-staging").also(tempDirs::add)
    val sourceFilesBefore = reviewSourceFiles(repoRoot).associateWith(Files::readAllBytes)

    val kmpManifest = loadPlatformManifest(repoRoot.resolve("platform-packs/kmp"))
    val staged =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = repoRoot,
          sourceSkillDir = repoRoot.resolve("skills/skill-bill"),
          home = home,
          manifests = listOf(kmpManifest),
          selectedPlatformSlugs = setOf("kmp"),
        ),
      )

    val feature = staged.renderedSkillFile.toPath().readText()

    assertContains(feature, "skill-bill <intake> --agent <currently-executing-agent> --no-live-output")
    assertContains(feature, "The runtime creates missing specs and the parent workflow before")
    assertContains(feature, "Linear, Jira, and any other connected tracker")
    assertContains(feature, "ask for the tracker issue key")
    assertFalse(feature.contains("skill-bill goal preflight <issue-key>"))
    assertFalse(feature.contains("Do not launch while unconfirmed"))
    assertContains(feature, "For each entry in `rehydrate_targets`")
    assertContains(feature, "Fetch nothing when the list is empty")
    assertContains(feature, "Relay its")
    assertFalse(feature.contains("spec_source"))
    assertFalse(feature.contains("continuation lookup"))
    assertFalse(feature.contains("mcp__linear"))
    assertFalse(feature.contains("goal_observability"))
    listOf(
      "peak-hours-warner.md",
      "shell-ceremony.md",
      "telemetry-contract.md",
      "android-compose-implementation.md",
      "android-navigation-implementation.md",
      "android-interop-implementation.md",
      "android-design-system-implementation.md",
      "android-r8-implementation.md",
      "android-compose-edge-to-edge.md",
      "android-compose-adaptive-layouts.md",
    ).forEach { pointer ->
      assertTrue(Files.isRegularFile(staged.stagingDir.resolve(pointer).toPath()), pointer)
    }
    listOf(
      "bill-feature-task.md",
      "bill-feature-task-runtime.md",
      "bill-feature-goal.md",
    ).forEach { removedSidecar ->
      assertFalse(Files.exists(staged.stagingDir.resolve(removedSidecar).toPath()), removedSidecar)
    }
    assertFalse(
      Files.exists(staged.stagingDir.resolve("bill-over-engineering-review.md").toPath()),
      "retired over-engineering skill must not be recreated during source installation",
    )
    assertContains(feature, "mode:auto|inline|delegated")
    assertFalse(feature.contains("execution-mode:auto|inline|delegated"))
    assertTrue(sourceFilesBefore.all { (path, bytes) -> bytes.contentEquals(Files.readAllBytes(path)) })
  }

  private fun reviewSourceFiles(repoRoot: Path): List<Path> =
    Files.walk(repoRoot.resolve("skills/skill-bill")).use { paths ->
      paths.filter(Files::isRegularFile).toList()
    }
}
