package skillbill.scaffold

import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentBundle
import skillbill.infrastructure.skills.nativeagent.composition.renderNativeAgentBundle
import skillbill.infrastructure.skills.scaffold.authoring.renderAuthoringTarget
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.testsupport.SnapshotAssertions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthoringRenderSnapshotTest {
  @Test
  fun `kotlin code-review render matches snapshot with manifest ordered pointers`() {
    val repoRoot = currentRepoRootForSnapshotTest()
    val rendered = renderAuthoringTarget(repoRoot, "bill-kotlin-code-review")
    val first = rendered.stdout
    val second = renderAuthoringTarget(repoRoot, "bill-kotlin-code-review").stdout

    assertEquals(
      expectedHeadersFromManifest(
        repoRoot,
        packSlug = "kotlin",
        skillRelativeDir = "code-review/bill-kotlin-code-review",
      ),
      rendered.blocks.map { block -> block.header },
    )
    assertEquals(first, second, "render output must be deterministic across repeated in-memory renders")
    SnapshotAssertions.assertMatchesSnapshot(
      "snapshots/scaffold/bill-kotlin-code-review.render.txt",
      first,
    )
  }

  @Test
  fun `kmp code-review render includes generated review composition before authored guidance`() {
    val repoRoot = currentRepoRootForSnapshotTest()
    val rendered = renderAuthoringTarget(repoRoot, "bill-kmp-code-review")
    val first = rendered.stdout
    val second = renderAuthoringTarget(repoRoot, "bill-kmp-code-review").stdout

    assertEquals(
      expectedHeadersFromManifest(repoRoot, packSlug = "kmp", skillRelativeDir = "code-review/bill-kmp-code-review"),
      rendered.blocks.map { block -> block.header },
    )
    assertTrue(
      first.indexOf("## Review Composition") < first.indexOf("## Execution"),
      "generated review composition must render before authored execution guidance",
    )
    assertTrue(
      "every flattened lane receives the same authoritative review packet" in first,
      "generated review composition must forward required review context",
    )
    assertTrue("MUST NOT launch a baseline orchestrator worker" in first)
    assertTrue(
      first.indexOf("## Governed Add-Ons") < first.indexOf("## Execution"),
      "generated governed add-on usage must render before authored execution guidance",
    )
    assertTrue(
      "`android-compose`: entrypoint `android-compose-review.md`" in first,
      "generated governed add-on section must list manifest-declared entrypoints",
    )
    assertEquals(first, second, "render output must be deterministic across repeated in-memory renders")
    SnapshotAssertions.assertMatchesSnapshot(
      "snapshots/scaffold/bill-kmp-code-review.render.txt",
      first,
    )
  }

  @Test
  fun `kmp ui code-review render and native agent bundle match snapshots`() {
    val repoRoot = currentRepoRootForSnapshotTest()
    val rendered = renderAuthoringTarget(repoRoot, "bill-kmp-code-review-ui")
    val first = rendered.stdout
    val second = renderAuthoringTarget(repoRoot, "bill-kmp-code-review-ui").stdout

    assertEquals(
      expectedHeadersFromManifest(repoRoot, packSlug = "kmp", skillRelativeDir = "code-review/bill-kmp-code-review-ui"),
      rendered.blocks.map { block -> block.header },
    )
    assertTrue(
      "## Governed Add-Ons" in first,
      "KMP UI specialist must receive generated add-on usage from platform.yaml",
    )
    assertEquals(first, second, "render output must be deterministic across repeated in-memory renders")
    SnapshotAssertions.assertMatchesSnapshot(
      "snapshots/scaffold/bill-kmp-code-review-ui.render.txt",
      first,
    )
    assertTrue("specialist-contract.md" in first)
    assertTrue("shell-ceremony.md" !in first)
    assertTrue("telemetry-contract.md" !in first)

    val nativeAgentPath =
      repoRoot.resolve(
        "platform-packs/kmp/code-review/bill-kmp-code-review/native-agents/agents.yaml",
      )
    val nativeAgentSource = renderNativeAgentBundle(parseNativeAgentBundle(nativeAgentPath))
    assertEquals(
      SnapshotAssertions.normalizeLineEndings(Files.readString(nativeAgentPath)),
      nativeAgentSource,
      "native agent source render must stay byte-identical after line-ending normalization",
    )
    SnapshotAssertions.assertMatchesSnapshot(
      "snapshots/native-agents/bill-kmp-code-review.agents.yaml",
      nativeAgentSource,
    )
  }

  private fun currentRepoRootForSnapshotTest(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      if (looksLikeRepoRoot(current)) {
        return current
      }
      current = current.parent
    }
    error("Could not locate skill-bill repo root for snapshot test")
  }

  private fun looksLikeRepoRoot(candidate: Path): Boolean =
    Files.isRegularFile(candidate.resolve("runtime-kotlin/settings.gradle.kts")) &&
      Files.isDirectory(candidate.resolve("skills")) &&
      Files.isDirectory(candidate.resolve("platform-packs"))

  private fun expectedHeadersFromManifest(
    repoRoot: Path,
    packSlug: String,
    skillRelativeDir: String,
  ): List<String> {
    val pack = loadPlatformManifest(repoRoot.resolve("platform-packs/$packSlug"))
    return listOf("===== SKILL.md: platform-packs/$packSlug/$skillRelativeDir/SKILL.md =====") +
      pack.pointers
        .filter { spec -> spec.skillRelativeDir == skillRelativeDir }
        .map { spec -> "===== pointer: platform-packs/$packSlug/$skillRelativeDir/${spec.name} =====" }
  }
}
