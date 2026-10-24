package skillbill.engine.featuretask.qa.capability

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.execution.QaDeclaredTarget
import skillbill.engine.featuretask.validation.kotlinPackWithoutGate
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaTargetKind
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
      qaCapability = QaCapabilityDeclaration(setOf(QaTargetKind("android")), "qa/android-qa.md"),
    )

  @Test
  fun `an Android app repository resolves the KMP pack QA guidance`() {
    val resolver = QaCapabilityResolver(RecordingWorkflowGitOperations()) { listOf(kotlin, kmp) }
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

    assertEquals(
      listOf(QaDeclaredTarget("kmp", setOf(QaTargetKind("android")), kmp.packRoot.resolve("qa/android-qa.md"))),
      resolution.targets,
    )
  }

  @Test
  fun `a pack absorbed as a baseline layer contributes no QA target`() {
    val kotlinDeclaringQa = kotlin.copy(qaCapability = QaCapabilityDeclaration(setOf(QaTargetKind("jvm")), "qa/jvm.md"))
    val resolver = QaCapabilityResolver(RecordingWorkflowGitOperations()) { listOf(kotlinDeclaringQa, kmp) }

    val resolution =
      assertIs<QaCapabilityResolution.Declared>(
        resolver.resolve(listOf("app/src/main/AndroidManifest.xml", "app/src/main/kotlin/MainActivity.kt")),
      )

    assertEquals(listOf("kmp"), resolution.targets.map { it.packSlug })
  }

  @Test
  fun `a repository equally owned by two QA-declaring packs resolves both targets in slug order`() {
    val web =
      kotlin.copy(
        slug = "web",
        routingSignals = RoutingSignals(strong = emptyList(), tieBreakers = emptyList(), path = listOf(".ts")),
        qaCapability = QaCapabilityDeclaration(setOf(QaTargetKind("web")), "qa/web-qa.md"),
      )
    val backend =
      kotlin.copy(
        slug = "backend",
        routingSignals = RoutingSignals(strong = emptyList(), tieBreakers = emptyList(), path = listOf(".py")),
        qaCapability = QaCapabilityDeclaration(setOf(QaTargetKind("backend")), "qa/backend-qa.md"),
      )
    val resolver = QaCapabilityResolver(RecordingWorkflowGitOperations()) { listOf(web, backend) }

    val resolution =
      assertIs<QaCapabilityResolution.Declared>(resolver.resolve(listOf("web/index.ts", "api/app.py")))

    assertEquals(listOf("backend", "web"), resolution.targets.map { it.packSlug })
    assertEquals(
      listOf(backend.packRoot.resolve("qa/backend-qa.md"), web.packRoot.resolve("qa/web-qa.md")),
      resolution.targets.map { it.guidancePath },
    )
  }

  @Test
  fun `a JVM repository stays an unsupported Kotlin target`() {
    val resolver = QaCapabilityResolver(RecordingWorkflowGitOperations()) { listOf(kotlin, kmp) }

    val resolution = resolver.resolve(listOf("build.gradle.kts", "server/src/main/kotlin/Application.kt"))

    assertEquals(QaCapabilityResolution.Absent("kotlin"), resolution)
  }
}
