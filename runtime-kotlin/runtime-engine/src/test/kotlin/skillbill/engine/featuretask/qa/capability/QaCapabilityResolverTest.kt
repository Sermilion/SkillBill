package skillbill.engine.featuretask.qa.capability

import skillbill.engine.featuretask.validation.kotlinPackWithoutGate
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaPlatform
import skillbill.scaffold.model.RoutingSignals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QaCapabilityResolverTest {
  private val kotlin =
    kotlinPackWithoutGate().copy(
      routingSignals =
        RoutingSignals(
          strong = emptyList(),
          tieBreakers = emptyList(),
          path = listOf(".kt", "*.kt", ".kts", "*.kts", "build.gradle", "build.gradle.kts", "settings.gradle.kts"),
        ),
    )

  private val kmp =
    kotlin.copy(
      slug = "kmp",
      routingSignals =
        RoutingSignals(
          strong = emptyList(),
          tieBreakers = emptyList(),
          path = listOf(".kt", "*.kt", ".kts", "*.kts", "AndroidManifest.xml", "androidMain", "iosMain", "commonMain"),
        ),
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
      qaCapability = QaCapabilityDeclaration(setOf(QaPlatform.ANDROID), "qa/android-qa.md"),
    )

  @Test
  fun `an Android app repository resolves the KMP pack QA guidance`() {
    val resolver = QaCapabilityResolver { listOf(kotlin, kmp) }
    val paths =
      listOf(
        "build.gradle.kts",
        "settings.gradle.kts",
        "app/build.gradle.kts",
        "app/src/main/AndroidManifest.xml",
        "app/src/main/kotlin/MainActivity.kt",
        "feature/account/src/main/kotlin/AccountScreen.kt",
        "feature/account/src/main/kotlin/LogoutViewModel.kt",
      )

    val resolution = assertIs<QaCapabilityResolution.Declared>(resolver.resolve(paths))

    assertEquals(setOf(QaPlatform.ANDROID), resolution.declaration.platforms)
    assertEquals(kmp.packRoot.resolve("qa/android-qa.md"), resolution.guidancePath)
  }

  @Test
  fun `a JVM repository stays an unsupported Kotlin target`() {
    val resolver = QaCapabilityResolver { listOf(kotlin, kmp) }

    val resolution = resolver.resolve(listOf("build.gradle.kts", "server/src/main/kotlin/Application.kt"))

    assertEquals(QaCapabilityResolution.Absent("kotlin"), resolution)
  }
}
