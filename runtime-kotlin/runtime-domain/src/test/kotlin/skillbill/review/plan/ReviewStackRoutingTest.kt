package skillbill.review.plan

import org.junit.jupiter.api.Test
import skillbill.model.FileLocation
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import kotlin.test.assertEquals

class ReviewStackRoutingTest {
  @Test
  fun `content without path ownership does not select a concrete pack`() {
    val result =
      ReviewStackRouting.route(
        listOf(
          pack("go", path = listOf("*.go"), content = listOf("package ", "json")),
          pack("kotlin", path = listOf("*.kt"), content = listOf("kotlin")),
          pack("neutral", path = emptyList(), content = emptyList(), fallback = true),
        ),
        listOf(
          ReviewRoutingChangedFile(
            "docs/review-routing.md",
            "A package may expose a JSON contract without containing Go source.",
          ),
        ),
      )

    assertEquals(setOf("neutral"), result.routedSlugs)
  }

  @Test
  fun `common prose tokens never establish concrete platform ownership`() {
    val concrete =
      listOf("php", "python", "rust", "typescript", "kotlin").map { slug ->
        pack(slug, path = listOf("*.$slug"), content = listOf("function", "use", "class", "import"))
      }
    val result =
      ReviewStackRouting.route(
        concrete + pack("neutral", path = emptyList(), content = emptyList(), fallback = true),
        listOf(
          ReviewRoutingChangedFile(
            "docs/design.md",
            "This class will import and use a function without choosing an implementation language.",
          ),
        ),
      )

    assertEquals(setOf("neutral"), result.routedSlugs)
  }

  @Test
  fun `content only breaks ties between positive path owners`() {
    val result =
      ReviewStackRouting.route(
        listOf(
          pack("first", path = listOf("src/*"), content = listOf("first marker")),
          pack("second", path = listOf("src/*"), content = listOf("second marker")),
          pack("content-only", path = listOf("*.other"), content = listOf("second marker")),
          pack("neutral", path = emptyList(), content = emptyList(), fallback = true),
        ),
        listOf(ReviewRoutingChangedFile("src/shared.txt", "second marker")),
      )

    assertEquals(setOf("second"), result.routedSlugs)
    assertEquals(mapOf("second" to setOf("src/shared.txt")), result.ownedPathsBySlug)
  }

  @Test
  fun `baseline pack wins a shared positive path tie with its composed root`() {
    val kotlin = pack("kotlin", path = listOf("*.kt"), content = emptyList())
    val kmp =
      pack(
        "kmp",
        path = listOf("*.kt"),
        content = emptyList(),
        baselinePlatform = "kotlin",
      )

    val result =
      ReviewStackRouting.route(
        listOf(kmp, kotlin),
        listOf(ReviewRoutingChangedFile("src/main/kotlin/Example.kt", "class Example")),
      )

    assertEquals(setOf("kotlin"), result.routedSlugs)
  }

  @Test
  fun `ordinary Kotlin identifiers do not select the multiplatform pack`() {
    val kotlin = pack("kotlin", path = listOf("*.kt"), content = emptyList())
    val kmp =
      pack("kmp", path = listOf("*.kt"), content = listOf("expect", "actual"), baselinePlatform = "kotlin")

    listOf("ExpectedState", "unexpected", "actualOwner", "_actual", "actual_", "éexpect", "expecté").forEach {
      val result =
        ReviewStackRouting.route(
          listOf(kmp, kotlin),
          listOf(ReviewRoutingChangedFile("src/Admission.kt", "val $it = null")),
        )

      assertEquals(setOf("kotlin"), result.routedSlugs, it)
    }
  }

  @Test
  fun `multiplatform declaration tokens retain composed ownership`() {
    val kotlin = pack("kotlin", path = listOf("*.kt"), content = emptyList())
    val kmp =
      pack("kmp", path = listOf("*.kt"), content = listOf("expect", "actual"), baselinePlatform = "kotlin")

    listOf("expect class Platform", "actual class Platform", "\tEXPECT fun platform()").forEach {
      val result =
        ReviewStackRouting.route(
          listOf(kmp, kotlin),
          listOf(ReviewRoutingChangedFile("src/Platform.kt", it)),
        )

      assertEquals(setOf("kmp", "kotlin"), result.routedSlugs, it)
      assertEquals(setOf("src/Platform.kt"), result.ownedPathsBySlug["kmp"])
    }
  }

  @Test
  fun `unresolved equal positive ownership selects only declared fallback`() {
    val result =
      ReviewStackRouting.route(
        listOf(
          pack("first", path = listOf("src/*"), content = emptyList()),
          pack("second", path = listOf("src/*"), content = emptyList()),
          pack("custom-neutral", path = emptyList(), content = emptyList(), fallback = true),
        ),
        listOf(ReviewRoutingChangedFile("src/shared.file", "class import function use")),
      )

    assertEquals(setOf("custom-neutral"), result.routedSlugs)
  }

  @Test
  fun `composition does not discard an unrelated equal positive owner`() {
    val kotlin = pack("kotlin", path = listOf("*.shared"), content = emptyList())
    val kmp =
      pack(
        "kmp",
        path = listOf("*.shared"),
        content = emptyList(),
        baselinePlatform = "kotlin",
      )
    val result =
      ReviewStackRouting.route(
        listOf(
          kmp,
          kotlin,
          pack("unrelated", path = listOf("*.shared"), content = emptyList()),
          pack("neutral", path = emptyList(), content = emptyList(), fallback = true),
        ),
        listOf(ReviewRoutingChangedFile("src/Example.shared", "shared implementation")),
      )

    assertEquals(setOf("neutral"), result.routedSlugs)
  }

  @Test
  fun `a required baseline pack that is neither external nor bundled routes to the declared fallback`() {
    val result =
      ReviewStackRouting.route(
        listOf(
          pack("kmp", path = listOf("*.kt"), content = emptyList(), baselinePlatform = "kotlin"),
          pack("generic", path = emptyList(), content = emptyList(), fallback = true),
        ),
        listOf(ReviewRoutingChangedFile("src/Main.kt", "class Main")),
      )

    assertEquals(setOf("generic"), result.routedSlugs)
    assertEquals(mapOf("generic" to setOf("src/Main.kt")), result.ownedPathsBySlug)
  }

  @Test
  fun `a changed file with no concrete platform pack routes to the declared fallback`() {
    val result =
      ReviewStackRouting.route(
        listOf(pack("generic", path = emptyList(), content = emptyList(), fallback = true)),
        listOf(ReviewRoutingChangedFile("src/Main.kt", "class Main")),
      )

    assertEquals(setOf("generic"), result.routedSlugs)
  }

  @Test
  fun `clear concrete ownership excludes fallback and weaker content match`() {
    val result =
      ReviewStackRouting.route(
        listOf(
          pack("kotlin", path = listOf("*.kt"), content = emptyList()),
          pack("php", path = listOf("*.php"), content = listOf("class", "function", "use")),
          pack("neutral", path = emptyList(), content = emptyList(), fallback = true),
        ),
        listOf(ReviewRoutingChangedFile("src/Main.kt", "class Main { fun use() = Unit }")),
      )

    assertEquals(setOf("kotlin"), result.routedSlugs)
  }

  @Test
  fun `content cannot promote lower path score over stronger path evidence`() {
    val result =
      ReviewStackRouting.route(
        listOf(
          pack("strong", path = listOf("src/*", "*.txt"), content = emptyList()),
          pack("weak", path = listOf("src/*"), content = listOf("import", "class", "function", "use")),
          pack("neutral", path = emptyList(), content = emptyList(), fallback = true),
        ),
        listOf(ReviewRoutingChangedFile("src/example.txt", "import class function use")),
      )

    assertEquals(setOf("strong"), result.routedSlugs)
  }

  @Test
  fun `mixed concrete files route to each clear owner`() {
    val result =
      ReviewStackRouting.route(
        listOf(
          pack("kotlin", path = listOf("*.kt"), content = emptyList()),
          pack("typescript", path = listOf("*.ts"), content = emptyList()),
          pack("neutral", path = emptyList(), content = emptyList(), fallback = true),
        ),
        listOf(
          ReviewRoutingChangedFile("src/Main.kt", "class Main"),
          ReviewRoutingChangedFile("web/main.ts", "export class Main {}"),
        ),
      )

    assertEquals(setOf("kotlin", "typescript"), result.routedSlugs)
    assertEquals(
      mapOf(
        "kotlin" to setOf("src/Main.kt"),
        "typescript" to setOf("web/main.ts"),
      ),
      result.ownedPathsBySlug,
    )
  }

  @Test
  fun `fallback owns only unresolved files alongside clear concrete owners`() {
    val result =
      ReviewStackRouting.route(
        listOf(
          pack("kotlin", path = listOf("*.kt"), content = emptyList()),
          pack("first", path = listOf("shared/*"), content = emptyList()),
          pack("second", path = listOf("shared/*"), content = emptyList()),
          pack("neutral", path = emptyList(), content = emptyList(), fallback = true),
        ),
        listOf(
          ReviewRoutingChangedFile("src/Main.kt", "class Main"),
          ReviewRoutingChangedFile("shared/model.txt", "shared model"),
          ReviewRoutingChangedFile("docs/readme.md", "documentation"),
        ),
      )

    assertEquals(setOf("kotlin", "neutral"), result.routedSlugs)
    assertEquals(
      mapOf(
        "kotlin" to setOf("src/Main.kt"),
        "neutral" to setOf("shared/model.txt", "docs/readme.md"),
      ),
      result.ownedPathsBySlug,
    )
  }

  @Test
  fun `unresolved file without fallback uses horizontal base route`() {
    val result =
      ReviewStackRouting.route(
        listOf(pack("kotlin", path = listOf("*.kt"), content = emptyList())),
        listOf(ReviewRoutingChangedFile("docs/only.md", "class import function use")),
      )

    assertEquals(emptySet(), result.routedSlugs)
    assertEquals(emptyMap(), result.ownedPathsBySlug)
  }

  @Test
  fun `ignored files produce no route without requiring fallback discovery`() {
    val result =
      ReviewStackRouting.route(
        listOf(pack("typescript", path = listOf("*.ts"), content = emptyList())),
        listOf(
          ReviewRoutingChangedFile("node_modules/library/index.ts", "export class Library"),
          ReviewRoutingChangedFile("dist/app.ts", "export class App"),
          ReviewRoutingChangedFile("src/generated/client.ts", "export class Client"),
        ),
      )

    assertEquals(emptySet(), result.routedSlugs)
    assertEquals(emptyMap(), result.ownedPathsBySlug)
  }

  private fun pack(
    slug: String,
    path: List<String>,
    content: List<String>,
    baselinePlatform: String? = null,
    fallback: Boolean = false,
  ) = PlatformManifest(
    slug = slug,
    packRoot = FileLocation("platform-packs/$slug"),
    contractVersion = "1.3",
    routingSignals = RoutingSignals(strong = path, tieBreakers = emptyList(), path = path, content = content),
    declaredCodeReviewAreas = emptyList(),
    declaredFiles =
      DeclaredFiles(
        baseline = FileLocation("platform-packs/$slug/code-review/bill-$slug-code-review/content.md"),
        areas = emptyMap(),
      ),
    areaMetadata = emptyMap(),
    codeReviewComposition =
      baselinePlatform?.let {
        CodeReviewComposition(
          listOf(
            CodeReviewBaselineLayer(
              platform = it,
              skill = "bill-$it-code-review",
              scope = CodeReviewCompositionScope.SameReviewScope,
              required = true,
              mode = CodeReviewCompositionMode.KmpBaseline,
            ),
          ),
        )
      },
    fallbackCapabilities = if (fallback) setOf("code-review") else emptySet(),
  )
}
