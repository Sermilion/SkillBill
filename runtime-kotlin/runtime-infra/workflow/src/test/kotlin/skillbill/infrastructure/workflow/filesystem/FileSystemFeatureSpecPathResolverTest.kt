package skillbill.infrastructure.workflow.filesystem

import skillbill.ports.featurespec.model.FeatureSpecOrigin
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.projectstore.FixedProjectStorePort
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FileSystemFeatureSpecPathResolverTest {
  private val storeRoot = Files.createTempDirectory("spec-resolver-store")
  private val resolver = FileSystemFeatureSpecPathResolver(FixedProjectStorePort(storeRoot))

  @Test
  fun `archived spec cannot supply a parent when active bundle contains only a stale manifest`() {
    val root = Files.createTempDirectory("archived-spec")
    val active = root.resolve(".feature-specs/SKILL-417-status")
    Files.createDirectories(active)
    Files.writeString(active.resolve("decomposition-manifest.yaml"), "stale projection")
    spec(root, "done/SKILL-417-status")

    val result = resolver.resolve(FeatureSpecPathResolveInput("SKILL-417", null, root))

    assertIs<FeatureSpecPathResolveResult.NoMatch>(result)
  }

  @Test
  fun `archived duplicates do not match or make an active spec ambiguous`() {
    val root = Files.createTempDirectory("spec-precedence")
    spec(root, "done/SKILL-417-first")
    spec(root, "done/SKILL-417-second")
    val input = FeatureSpecPathResolveInput("SKILL-417", null, root)

    assertIs<FeatureSpecPathResolveResult.NoMatch>(resolver.resolve(input))
    val active = spec(root, "SKILL-417-active")
    assertEquals(
      active.toString(),
      assertIs<FeatureSpecPathResolveResult.SingleMatch>(resolver.resolve(input)).specPath,
    )
    spec(root, "SKILL-417-second-active")
    assertEquals(2, assertIs<FeatureSpecPathResolveResult.Ambiguous>(resolver.resolve(input)).matches.size)
  }

  @Test
  fun `store bundle wins over a legacy bundle for the same issue and reports the store origin`() {
    val root = Files.createTempDirectory("spec-store-wins")
    spec(root, "SKILL-417-legacy")
    val stored = storeSpec("SKILL-417-stored")

    val match =
      assertIs<FeatureSpecPathResolveResult.SingleMatch>(
        resolver.resolve(FeatureSpecPathResolveInput("SKILL-417", null, root)),
      )

    assertEquals(stored.toString(), match.specPath)
    assertEquals(FeatureSpecOrigin.STORE, match.origin)
  }

  @Test
  fun `legacy only bundle resolves and reports the legacy origin`() {
    val root = Files.createTempDirectory("spec-legacy-only")
    val legacy = spec(root, "SKILL-417-legacy")

    val match =
      assertIs<FeatureSpecPathResolveResult.SingleMatch>(
        resolver.resolve(FeatureSpecPathResolveInput("SKILL-417", null, root)),
      )

    assertEquals(legacy.toString(), match.specPath)
    assertEquals(FeatureSpecOrigin.LEGACY, match.origin)
  }

  @Test
  fun `explicit spec path derives its origin from where it lives`() {
    val root = Files.createTempDirectory("spec-explicit-origin")
    val stored = storeSpec("SKILL-417-stored")
    val legacy = spec(root, "SKILL-417-legacy")

    val fromStore =
      assertIs<FeatureSpecPathResolveResult.Explicit>(
        resolver.resolve(FeatureSpecPathResolveInput("SKILL-417", stored.toString(), root)),
      )
    val fromLegacy =
      assertIs<FeatureSpecPathResolveResult.Explicit>(
        resolver.resolve(FeatureSpecPathResolveInput("SKILL-417", legacy.toString(), root)),
      )

    assertEquals(FeatureSpecOrigin.STORE, fromStore.origin)
    assertEquals(FeatureSpecOrigin.LEGACY, fromLegacy.origin)
  }

  private fun spec(
    root: Path,
    directory: String,
  ): Path = write(root.resolve(".feature-specs/$directory/spec.md"))

  private fun storeSpec(directory: String): Path = write(storeRoot.resolve("specs/$directory/spec.md"))

  private fun write(path: Path): Path {
    Files.createDirectories(path.parent)
    Files.writeString(path, "# Spec")
    return path
  }
}
