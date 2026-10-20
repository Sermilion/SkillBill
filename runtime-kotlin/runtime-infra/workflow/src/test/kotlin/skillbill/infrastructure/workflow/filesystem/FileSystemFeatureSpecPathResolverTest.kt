package skillbill.infrastructure.workflow.filesystem

import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FileSystemFeatureSpecPathResolverTest {
  @Test
  fun `archived spec cannot supply a parent when active bundle contains only a stale manifest`() {
    val root = Files.createTempDirectory("archived-spec")
    val active = root.resolve(".feature-specs/SKILL-417-status")
    Files.createDirectories(active)
    Files.writeString(active.resolve("decomposition-manifest.yaml"), "stale projection")
    spec(root, "done/SKILL-417-status")

    val result = FileSystemFeatureSpecPathResolver().resolve(FeatureSpecPathResolveInput("SKILL-417", null, root))

    assertIs<FeatureSpecPathResolveResult.NoMatch>(result)
  }

  @Test
  fun `archived duplicates do not match or make an active spec ambiguous`() {
    val root = Files.createTempDirectory("spec-precedence")
    spec(root, "done/SKILL-417-first")
    spec(root, "done/SKILL-417-second")
    val input = FeatureSpecPathResolveInput("SKILL-417", null, root)
    val resolver = FileSystemFeatureSpecPathResolver()

    assertIs<FeatureSpecPathResolveResult.NoMatch>(resolver.resolve(input))
    val active = spec(root, "SKILL-417-active")
    assertEquals(
      active.toString(),
      assertIs<FeatureSpecPathResolveResult.SingleMatch>(resolver.resolve(input)).specPath,
    )
    spec(root, "SKILL-417-second-active")
    assertEquals(2, assertIs<FeatureSpecPathResolveResult.Ambiguous>(resolver.resolve(input)).matches.size)
  }

  private fun spec(
    root: Path,
    directory: String,
  ): Path {
    val path = root.resolve(".feature-specs/$directory/spec.md")
    Files.createDirectories(path.parent)
    Files.writeString(path, "# Spec")
    return path
  }
}
