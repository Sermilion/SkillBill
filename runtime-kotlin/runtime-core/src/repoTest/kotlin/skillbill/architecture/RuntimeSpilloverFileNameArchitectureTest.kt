package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeSpilloverFileNameArchitectureTest {
  @Test
  fun `spillover filename census stays empty`() {
    val scanRoots =
      PrincipleEnforcementInventory.moduleArchitectureScanCases.map { scanCase ->
        scanCase.moduleSourceRoot
      }
    val current =
      ArchitectureScanSupport.spilloverFileNamePaths(
        scanRoots = scanRoots,
        exemptPaths = PrincipleEnforcementInventory.spilloverFileNameExemptions,
      )
    assertEquals(emptySet(), current, current.joinToString("\n"))
  }

  @Test
  fun `spillover identifier census stays empty`() {
    val scanRoots =
      PrincipleEnforcementInventory.moduleArchitectureScanCases.map { scanCase ->
        scanCase.moduleSourceRoot
      }
    val current =
      ArchitectureScanSupport.spilloverIdentifierKeys(
        scanRoots = scanRoots,
        exemptPaths = PrincipleEnforcementInventory.spilloverFileNameExemptions,
      )
    assertEquals(emptySet(), current, current.joinToString("\n"))
  }

  @Test
  fun `spillover identifier scanner fires on suffixed main-source declarations`() {
    val path = "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/example/BarSupport.kt"
    val violations =
      ArchitectureScanSupport.spilloverIdentifierViolationsInSource(
        relativePath = path,
        source =
          """

          class BarSupport(private val seed: Int) {
            val fooContinued2: Int = seed
            fun bazHelpers(): Int = fooContinued2
          }

          object QuxMisc

          val quuxA1: Int = 1
          """.trimIndent(),
      )
    assertEquals(
      listOf("BarSupport", "fooContinued2", "bazHelpers", "QuxMisc", "quuxA1").map { name ->
        "$path#$name carries the spillover-identifier signature; name the declaration for the responsibility it holds."
      },
      violations,
    )
  }

  @Test
  fun `spillover identifier scanner accepts numbered domain names and test-source declarations`() {
    val mainViolations =
      ArchitectureScanSupport.spilloverIdentifierViolationsInSource(
        relativePath = "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/Protocol2.kt",
        source =
          """

          class Protocol2(val digestSha256: String) {
            val stage1: Int = 1
            fun sha256(): String = digestSha256
            fun <T : Any> List<T>.firstA1(): T = first()

            companion object {
              const val NORMALIZED_LICENSE_SHA256: String = "fooContinued2"
            }
          }
          """.trimIndent(),
      )
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/Protocol2.kt#firstA1 carries the " +
          "spillover-identifier signature; name the declaration for the responsibility it holds.",
      ),
      mainViolations,
    )
    val testViolations =
      ArchitectureScanSupport.spilloverIdentifierViolationsInSource(
        relativePath = "runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/FooTestSupport.kt",
        source = "package skillbill.architecture\n\nobject FooTestSupport {\n  val barHelpers: Int = 1\n}\n",
      )
    assertEquals(emptyList(), testViolations)
  }

  @Test
  fun `spillover filename scanner applies bare suffixes to main sources only`() {
    val violations =
      ArchitectureScanSupport.spilloverFileNameViolationsForPaths(
        relativePaths =
          listOf(
            "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/BarSupport.kt",
            "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/QuxMisc.kt",
            "runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/FooTestSupport.kt",
            "runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/FooTestSupport2.kt",
          ),
        exemptPaths = emptySet(),
      )
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/BarSupport.kt carries the " +
          "spillover-filename signature; name the unit for the responsibility it holds.",
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/QuxMisc.kt carries the " +
          "spillover-filename signature; name the unit for the responsibility it holds.",
        "runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/FooTestSupport2.kt carries the " +
          "spillover-filename signature; name the unit for the responsibility it holds.",
      ),
      violations,
    )
  }

  @Test
  fun `spillover filename scanner fires on synthetic suffixed names`() {
    val violations =
      ArchitectureScanSupport.spilloverFileNameViolationsForPaths(
        relativePaths =
          listOf(
            "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/Foo.kt",
            "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/FooExtras2.kt",
            "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/BarExtras.kt",
            "runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/core/RuntimeBootstrapBindings.kt",
            "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/FooContinued2.kt",
          ),
        exemptPaths = emptySet(),
      )
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/FooContinued2.kt carries the " +
          "spillover-filename signature; name the unit for the responsibility it holds.",
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/BarExtras.kt carries the " +
          "spillover-filename signature; name the unit for the responsibility it holds.",
        "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/FooExtras2.kt carries the " +
          "spillover-filename signature; name the unit for the responsibility it holds.",
      ),
      violations,
    )
  }

  @Test
  fun `spillover filename scanner ignores legitimately numbered domain names without siblings`() {
    val violations =
      ArchitectureScanSupport.spilloverFileNameViolationsForPaths(
        relativePaths =
          listOf(
            "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/Protocol2.kt",
          ),
        exemptPaths = emptySet(),
      )
    assertEquals(emptyList(), violations)
  }

  @Test
  fun `spillover filename scanner flags bare trailing digits only within the same package`() {
    val samePackageViolations =
      ArchitectureScanSupport.spilloverFileNameViolationsForPaths(
        relativePaths =
          listOf(
            "runtime-kotlin/runtime-example/src/main/kotlin/skillbill/example/Foo.kt",
            "runtime-kotlin/runtime-example/src/main/kotlin/skillbill/example/Foo2.kt",
          ),
        exemptPaths = emptySet(),
      )
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-example/src/main/kotlin/skillbill/example/Foo2.kt carries the " +
          "spillover-filename signature; name the unit for the responsibility it holds.",
      ),
      samePackageViolations,
    )
    val crossPackageViolations =
      ArchitectureScanSupport.spilloverFileNameViolationsForPaths(
        relativePaths =
          listOf(
            "runtime-kotlin/runtime-example/src/main/kotlin/skillbill/other/Foo.kt",
            "runtime-kotlin/runtime-example/src/main/kotlin/skillbill/example/Foo2.kt",
          ),
        exemptPaths = emptySet(),
      )
    assertEquals(emptyList(), crossPackageViolations)
  }

  @Test
  fun `spillover filename scanner honours a named exemption`() {
    val exempt = "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/example/FooExtras.kt"
    val violations =
      ArchitectureScanSupport.spilloverFileNameViolationsForPaths(
        relativePaths = listOf(exempt),
        exemptPaths = setOf(exempt),
      )
    assertEquals(emptyList(), violations)
  }
}
