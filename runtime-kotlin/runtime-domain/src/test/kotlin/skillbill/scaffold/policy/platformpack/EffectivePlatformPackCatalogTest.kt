package skillbill.scaffold.policy.platformpack

import org.junit.jupiter.api.io.TempDir
import skillbill.error.core.ExternalPlatformPackFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.model.FileLocation
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import skillbill.scaffold.policy.platformpack.model.LoadedPlatformPack
import skillbill.scaffold.policy.platformpack.model.PlatformPackSourceKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EffectivePlatformPackCatalogTest {
  @Test
  fun `external slug replaces bundled even when bundled routing signals are stronger`() {
    val bundled =
      loaded(
        "kotlin",
        PlatformPackSourceKind.BUNDLED,
        Path.of("/repo/platform-packs/kotlin"),
        strong = listOf(".kt", "settings.gradle.kts", "detekt.yml"),
      )
    val external =
      loaded(
        "kotlin",
        PlatformPackSourceKind.EXTERNAL,
        Path.of("/ext/kotlin"),
        strong = listOf(".kt"),
      )
    val catalog = buildEffectivePlatformPackCatalog(listOf(bundled), listOf(external))

    val winner = catalog.entryForSlug("kotlin")
    assertEquals(PlatformPackSourceKind.EXTERNAL, winner?.loaded?.sourceKind)
    assertEquals(listOf(".kt"), winner?.loaded?.manifest?.routingSignals?.strong)
    assertEquals("kotlin", winner?.shadowedBundledSlug)
  }

  @Test
  fun `new external slug stays beside unmatched bundled packs`() {
    val bundled = loaded("kotlin", PlatformPackSourceKind.BUNDLED, Path.of("/repo/platform-packs/kotlin"))
    val external = loaded("acme", PlatformPackSourceKind.EXTERNAL, Path.of("/ext/acme"))
    val catalog = buildEffectivePlatformPackCatalog(listOf(bundled), listOf(external))

    assertEquals(PlatformPackSourceKind.EXTERNAL, catalog.entryForSlug("acme")?.loaded?.sourceKind)
    assertEquals(null, catalog.entryForSlug("acme")?.shadowedBundledSlug)
    assertEquals(PlatformPackSourceKind.BUNDLED, catalog.entryForSlug("kotlin")?.loaded?.sourceKind)
  }

  @Test
  fun `ambiguous external slug fails`(
    @TempDir tmp: Path,
  ) {
    val first = loaded("kotlin", PlatformPackSourceKind.EXTERNAL, tmp.resolve("a"))
    val second = loaded("kotlin", PlatformPackSourceKind.EXTERNAL, tmp.resolve("b"))
    assertFailsWith<SkillBillRuntimeException> {
      buildEffectivePlatformPackCatalog(emptyList(), listOf(first, second))
    }.also { assertEquals(ExternalPlatformPackFailureCode.AMBIGUOUS, it.code) }
  }

  private fun loaded(
    slug: String,
    kind: PlatformPackSourceKind,
    root: Path,
    strong: List<String> = emptyList(),
  ): LoadedPlatformPack =
    LoadedPlatformPack(
      manifest =
        PlatformManifest(
          slug = slug,
          packRoot = FileLocation(root.toString()),
          contractVersion = "1",
          routingSignals = RoutingSignals(strong, emptyList()),
          declaredCodeReviewAreas = emptyList(),
          declaredFiles = DeclaredFiles(baseline = null, areas = emptyMap()),
          areaMetadata = emptyMap(),
        ),
      sourceKind = kind,
      canonicalRoot = root.toAbsolutePath().normalize().toString(),
    )
}
