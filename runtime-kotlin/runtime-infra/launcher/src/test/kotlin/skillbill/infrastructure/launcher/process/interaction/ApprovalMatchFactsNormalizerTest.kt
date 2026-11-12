package skillbill.infrastructure.launcher.process.interaction

import skillbill.approval.model.ApprovalMatchFacts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApprovalMatchFactsNormalizerTest {
  @Test
  fun `relative paths inside checkout yield ordered relative facts`() {
    val checkout = Files.createTempDirectory("facts-checkout").toRealPath()
    try {
      Files.createDirectories(checkout.resolve("src"))
      val existing = checkout.resolve("src/A.kt")
      Files.writeString(existing, "x")
      val root = checkout.toString()
      assertEquals(
        ApprovalMatchFacts.Paths(listOf("src/A.kt")),
        ApprovalMatchFactsNormalizer.pathFacts(root, root, listOf("src/A.kt")),
      )
      assertEquals(
        ApprovalMatchFacts.Paths(listOf("src/New.kt")),
        ApprovalMatchFactsNormalizer.pathFacts(root, root, listOf("src/New.kt")),
      )
    } finally {
      deleteRecursively(checkout)
    }
  }

  @Test
  fun `escape symlink dot dot checkout root or mixed bad paths null the request`() {
    val checkout = Files.createTempDirectory("facts-checkout").toRealPath()
    val outside = Files.createTempDirectory("facts-outside").toRealPath()
    try {
      Files.createDirectories(checkout.resolve("src"))
      Files.createSymbolicLink(checkout.resolve("out"), outside)
      val root = checkout.toString()
      assertNull(ApprovalMatchFactsNormalizer.pathFacts(root, root, listOf("../outside.txt")))
      assertNull(ApprovalMatchFactsNormalizer.pathFacts(root, root, listOf("out/secret.txt")))
      assertNull(ApprovalMatchFactsNormalizer.pathFacts(root, root, listOf("src/A.kt", "../escape")))
      assertNull(ApprovalMatchFactsNormalizer.pathFacts(root, root, listOf(".")))
    } finally {
      deleteRecursively(outside)
      deleteRecursively(checkout)
    }
  }

  private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }
}
