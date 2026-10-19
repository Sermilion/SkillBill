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
  fun `a composed pack keeps its own gate instead of borrowing its baseline gate`() {
    val kmp = kmpPack(path = listOf("commonMain"))
    val resolver = ValidationGateResolver { listOf(ios, kmp, kotlin) }

    val result = assertIs<ValidationGateResolution.Declared>(resolver.resolve(listOf("shared/commonMain/Foo.kt")))

    assertEquals("kmp", result.packSlug)
    val absent =
      ValidationGateResolver { listOf(ios, kmp.copy(validationGate = null), kotlin) }
        .resolve(listOf("shared/commonMain/Foo.kt"))
    assertEquals(ValidationGateResolution.Absent("kmp"), absent)
  }

  @Test
  fun `a whole KMP repository routes to the composed pack despite Kotlin-only Gradle and module files`() {
    val resolver = ValidationGateResolver { listOf(reviewFallbackPackWithoutGate(), ios, kmpRepoPack, kotlinRepoPack) }

    val result = assertIs<ValidationGateResolution.Declared>(resolver.resolve(kmpRepositoryPaths))

    assertEquals("kmp", result.packSlug)
  }

  @Test
  fun `a whole JVM repository without KMP markers still routes to the Kotlin pack`() {
    val resolver = ValidationGateResolver { listOf(reviewFallbackPackWithoutGate(), ios, kmpRepoPack, kotlinRepoPack) }
    val paths =
      listOf(
        "build.gradle.kts",
        "settings.gradle.kts",
        "server/build.gradle.kts",
        "server/src/main/kotlin/Application.kt",
        "server/src/test/kotlin/ApplicationTest.kt",
      )

    val result = assertIs<ValidationGateResolution.Declared>(resolver.resolve(paths))

    assertEquals("kotlin", result.packSlug)
  }

  @Test
  fun `a composed pack absorbs its baseline before competing with an unrelated larger pack`() {
    val resolver = ValidationGateResolver { listOf(ios, kmpRepoPack, kotlinRepoPack) }
    val paths = kmpRepositoryPaths + (1..20).map { "iosApp/Feature$it.swift" }

    val result = assertIs<ValidationGateResolution.Declared>(resolver.resolve(paths))

    assertEquals("ios", result.packSlug)
  }

  private val kotlinRepoPack =
    kotlin.copy(
      routingSignals =
        RoutingSignals(
          strong = emptyList(),
          tieBreakers = emptyList(),
          path = listOf(".kt", "*.kt", ".kts", "*.kts", "build.gradle", "build.gradle.kts", "settings.gradle.kts"),
        ),
    )

  private val kmpRepoPack =
    kmpPack(
      path = listOf(".kt", "*.kt", ".kts", "*.kts", "AndroidManifest.xml", "androidMain", "iosMain", "commonMain"),
    )

  private val kmpRepositoryPaths =
    listOf(
      "build.gradle.kts",
      "settings.gradle.kts",
      "gradle/libs.versions.toml",
      "app/build.gradle.kts",
      "app/src/main/AndroidManifest.xml",
      "app/src/main/kotlin/MainActivity.kt",
      "feature/account/build.gradle.kts",
      "feature/account/src/main/kotlin/AccountScreen.kt",
      "feature/account/src/main/kotlin/LogoutViewModel.kt",
      "feature/account/src/test/kotlin/LogoutViewModelTest.kt",
      "shared/build.gradle.kts",
      "shared/src/commonMain/kotlin/Session.kt",
    )

  private fun kmpPack(path: List<String>) =
    kotlin.copy(
      slug = "kmp",
      routingSignals = RoutingSignals(path, emptyList(), path = path),
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
}
