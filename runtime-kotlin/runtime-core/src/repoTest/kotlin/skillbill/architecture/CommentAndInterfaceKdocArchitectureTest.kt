package skillbill.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommentAndInterfaceKdocArchitectureTest {
  @Test
  fun `authored Kotlin under scan roots contains no forbidden comments or non-interface KDoc`() {
    val violations =
      ArchitectureScanSupport.commentAndInterfaceKdocViolations(
        scanRoots = PrincipleEnforcementInventory.inlineFqnScanRoots,
      )
    assertEquals(
      emptyList(),
      violations,
      "Scoped Kotlin must contain no // line comments, no non-KDoc block comments, and no KDoc except on " +
        "interfaces and their members.",
    )
  }

  @Test
  fun `line comment scanner ignores slashes inside string literals`() {
    val fixture =
      """

      class Holder {
        val payload = "http://example.com/path // not a comment"
      }
      """.trimIndent()
    assertEquals(
      emptyList(),
      ArchitectureScanSupport.lineCommentViolationsIgnoringStringLiterals(fixture),
      "Regression if // inside a quoted literal is treated as a line comment.",
    )
  }

  @Test
  fun `apostrophes in escaped identifiers preserve literal and comment detection`() {
    val fixture =
      """
      class Holder {
        fun `reader's detail`() {
          val url = "https://ci.example/run"
          val message = "job's failure"
        }
      }
      """.trimIndent()

    assertEquals(emptyList(), ArchitectureScanSupport.collectCommentPolicyViolations(fixture))
    assertEquals(
      listOf("line-comment"),
      ArchitectureScanSupport.collectCommentPolicyViolations(fixture + "\n" + '/' + '/' + " forbidden")
        .map { it.kind },
    )
  }

  @Test
  fun `line comment outside literals fails the violation collector`() {
    val fixture =
      """

      class Dirty {
        fun x() = 1 // forbidden
      }
      """.trimIndent()
    assertTrue(
      ArchitectureScanSupport.collectCommentPolicyViolations(fixture).any { violation ->
        violation.kind == "line-comment"
      },
      "Regression if a real // line comment no longer fails the comment-policy gate.",
    )
  }

  @Test
  fun `class level KDoc fails the violation collector`() {
    val fixture =
      """

      /**
       * Not on an interface.
       */
      class Dirty
      """.trimIndent()
    assertTrue(
      ArchitectureScanSupport.collectCommentPolicyViolations(fixture).any { violation ->
        violation.kind == "kdoc-outside-interface"
      },
      "Regression if KDoc on a class is allowed outside interface declarations.",
    )
  }

  @Test
  fun `function level KDoc fails the violation collector`() {
    val fixture =
      """

      /** Not on an interface member. */
      fun dirty() = 1
      """.trimIndent()
    assertTrue(
      ArchitectureScanSupport.collectCommentPolicyViolations(fixture).any { violation ->
        violation.kind == "kdoc-outside-interface"
      },
      "Regression if KDoc on a top-level function is allowed outside interface declarations.",
    )
  }

  @Test
  fun `interface member KDoc passes the violation collector`() {
    val fixture =
      """

      interface Port {
        /**
         * Contract surface.
         */
        fun run()
      }
      """.trimIndent()
    assertEquals(
      emptyList(),
      ArchitectureScanSupport.collectCommentPolicyViolations(fixture),
      "Regression if allowed interface-member KDoc is stripped or flagged.",
    )
  }

  @Test
  fun `nested class member KDoc fails the violation collector`() {
    val fixture =
      """

      interface Container {
        class Nested {
          /** Not a member of the enclosing interface. */
          fun run()
        }
      }
      """.trimIndent()
    assertTrue(
      ArchitectureScanSupport.collectCommentPolicyViolations(fixture).any { violation ->
        violation.kind == "kdoc-outside-interface"
      },
      "Regression if KDoc inside a nested concrete type is treated as interface-member KDoc.",
    )
  }

  @Test
  fun `KDoc in a top-level property initializer fails after a bodyless interface`() {
    val fixture =
      """

      interface Marker

      val factory = {
        /** Not owned by an interface. */
        val value = 1
      }
      """.trimIndent()
    assertTrue(
      ArchitectureScanSupport.collectCommentPolicyViolations(fixture).any { violation ->
        violation.kind == "kdoc-outside-interface"
      },
      "Regression if a bodyless interface leaves the following property initializer marked as interface scope.",
    )
  }

  @Test
  fun `sealed interface KDoc passes the violation collector`() {
    val fixture =
      """

      /** Contract surface. */
      sealed interface Port
      """.trimIndent()
    assertEquals(
      emptyList(),
      ArchitectureScanSupport.collectCommentPolicyViolations(fixture),
      "Regression if KDoc on a sealed interface declaration is flagged.",
    )
  }

  @Test
  fun `nested annotation KDoc passes the violation collector`() {
    val fixture =
      """

      interface Container {
        /** Nested contract type. */
        annotation class Marker
      }
      """.trimIndent()
    assertEquals(
      emptyList(),
      ArchitectureScanSupport.collectCommentPolicyViolations(fixture),
      "Regression if KDoc on a nested interface-owned type is flagged.",
    )
  }

  @Test
  fun `non KDoc block comment fails the violation collector`() {
    val fixture =
      """

      class Dirty {
        /* block */
        fun x() = 1
      }
      """.trimIndent()
    assertTrue(
      ArchitectureScanSupport.collectCommentPolicyViolations(fixture).any { violation ->
        violation.kind == "block-comment"
      },
      "Regression if a /* */ block comment is not reported.",
    )
  }

  @Test
  fun `authored Kotlin scanner skips build and generated path segments`() {
    val tempRoot = Files.createTempDirectory("comment-policy-scan")
    try {
      tempRoot
        .resolve("module/build/generated/Dirty.kt")
        .also { path ->
          Files.createDirectories(path.parent)
          Files.writeString(
            path,
            """

            class Dirty {
              fun x() = 1 // forbidden if scanned
            }
            """.trimIndent(),
          )
        }
      val authoredSource =
        tempRoot
          .resolve("module/src/Clean.kt")
          .also { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, "package example\n\nclass Clean\n")
          }
      assertEquals(
        listOf(authoredSource),
        ArchitectureScanSupport.authoredKotlinSourcesUnder(tempRoot),
        "Regression if Kotlin under build/ or generated/ is treated as authored comment-policy scope.",
      )
    } finally {
      tempRoot.toFile().deleteRecursively()
    }
  }
}
