package skillbill.engine.featuretask.validation

import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.RoutingSignals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ValidationGateRoutingTest {
  private val kotlin = kotlinPackWithoutGate().copy(validationGate = validationGateTestDeclaration)
  private val ios =
    kotlin.copy(
      slug = "ios",
      routingSignals = RoutingSignals(listOf("*.swift"), emptyList(), path = listOf("*.swift")),
    )

  @Test
  fun `unmatched documents cannot outvote concrete source ownership`() {
    val resolver = ValidationGateResolver { listOf(reviewFallbackPackWithoutGate(), ios, kotlin) }
    val paths = listOf("README.md", "docs/design.md", "notes.txt", "runtime-kotlin/Foo.kt")

    val result = assertIs<ValidationGateResolution.Declared>(resolver.resolve(paths))

    assertEquals("kotlin", result.packSlug)
  }

  @Test
  fun `equally dominant unrelated packs block independently of catalog order`() {
    listOf(listOf(ios, kotlin), listOf(kotlin, ios)).forEach { packs ->
      val resolver = ValidationGateResolver { packs }

      val result = resolver.resolve(listOf("ios/App.swift", "runtime-kotlin/Foo.kt"))

      assertIs<ValidationGateResolution.Incompatible>(result)
    }
  }

  @Test
  fun `a dominant pack without a gate does not borrow from a smaller routed pack`() {
    val resolver = ValidationGateResolver { listOf(ios, kotlin.copy(validationGate = null)) }

    val result = resolver.resolve(listOf("ios/App.swift", "runtime-kotlin/Foo.kt", "runtime-kotlin/Bar.kt"))

    assertEquals(ValidationGateResolution.Absent("kotlin"), result)
  }

  @Test
  fun `a scope owned only by the fallback pack routes by the repository's tracked files`() {
    val resolver = ValidationGateResolver { listOf(reviewFallbackPackWithoutGate(), ios, kotlin) }
    val tracked = listOf("runtime-kotlin/Foo.kt", "runtime-kotlin/Bar.kt", "ios/App.swift", "README.md")

    listOf(emptyList(), listOf(".feature-specs/SKILL-1-demo/decomposition-manifest.yaml")).forEach { changed ->
      val result =
        assertIs<ValidationGateResolution.Declared>(resolver.resolveWithRepositoryFallback(changed) { tracked })

      assertEquals("kotlin", result.packSlug)
    }
  }

  @Test
  fun `concrete changed-file ownership wins over the repository's tracked files`() {
    val resolver = ValidationGateResolver { listOf(reviewFallbackPackWithoutGate(), ios, kotlin) }
    var trackedReads = 0
    val tracked = {
      trackedReads++
      listOf("runtime-kotlin/Foo.kt", "runtime-kotlin/Bar.kt")
    }

    val result =
      assertIs<ValidationGateResolution.Declared>(
        resolver.resolveWithRepositoryFallback(listOf("ios/App.swift", "README.md"), tracked),
      )
    val tie = resolver.resolveWithRepositoryFallback(listOf("ios/App.swift", "runtime-kotlin/Foo.kt"), tracked)

    assertEquals("ios", result.packSlug)
    assertIs<ValidationGateResolution.Incompatible>(tie)
    assertEquals(0, trackedReads)
  }

  @Test
  fun `a repository without concrete ownership keeps the fallback resolution`() {
    val resolver = ValidationGateResolver { listOf(reviewFallbackPackWithoutGate(), ios, kotlin) }

    val result = resolver.resolveWithRepositoryFallback(listOf("README.md")) { listOf("README.md", "docs/a.md") }

    assertEquals(ValidationGateResolution.Absent("generic"), result)
  }

  @Test
  fun `a composed pack keeps its own gate instead of borrowing its baseline gate`() {
    val kmp =
      kotlin.copy(
        slug = "kmp",
        routingSignals = RoutingSignals(listOf("commonMain"), emptyList(), path = listOf("commonMain")),
        codeReviewComposition =
          CodeReviewComposition(
            listOf(
              CodeReviewBaselineLayer(
                platform = "kotlin",
                skill = "bill-kotlin-code-review",
                scope = CodeReviewCompositionScope.SameReviewScope,
                required = true,
                mode = CodeReviewCompositionMode.KmpBaseline,
              ),
            ),
          ),
      )
    val resolver = ValidationGateResolver { listOf(ios, kmp, kotlin) }

    val result = assertIs<ValidationGateResolution.Declared>(resolver.resolve(listOf("shared/commonMain/Foo.kt")))

    assertEquals("kmp", result.packSlug)
    val absent =
      ValidationGateResolver { listOf(ios, kmp.copy(validationGate = null), kotlin) }
        .resolve(listOf("shared/commonMain/Foo.kt"))
    assertEquals(ValidationGateResolution.Absent("kmp"), absent)
  }
}
