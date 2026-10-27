package skillbill.review.plan

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewPathMatcherTest {
  @Test
  fun `internal wildcard and directory signals use the same normalized semantics`() {
    assertTrue(ReviewPathMatcher.matches("src/commonMain/kotlin/Screen.kt", "src/*/kotlin/*.kt"))
    assertTrue(ReviewPathMatcher.matches("src/commonMain/kotlin/Screen.kt", "/commonMain/"))
    assertFalse(ReviewPathMatcher.matches("src/commonMain/kotlin/Screen.kt", "src/androidMain/**"))
  }

  @Test
  fun `prepared signals match successive paths without carrying the previous match`() {
    val wildcard = CompiledReviewPathMatcher("*.KT")
    val directory = CompiledReviewPathMatcher("/commonMain/")

    listOf("src/commonMain/kotlin/Screen.kt", "src/jvmMain/Window.KT").forEach { path ->
      assertTrue(wildcard.matches(path))
      assertFalse(wildcard.matches("src/types.d.ts"))
    }
    assertTrue(directory.matches("src/COMMONMAIN/kotlin/Screen.kt"))
    assertFalse(directory.matches("src/jvmMain/Window.kt"))
    assertTrue(directory.matches("src/commonMain/kotlin/Other.kt"))
    assertTrue(CompiledReviewPathMatcher("*.gradle.kts").matches("build.gradle.kts"))
    assertFalse(CompiledReviewPathMatcher("*.gradle.kts").matches("buildXgradle.kts"))
  }
}
