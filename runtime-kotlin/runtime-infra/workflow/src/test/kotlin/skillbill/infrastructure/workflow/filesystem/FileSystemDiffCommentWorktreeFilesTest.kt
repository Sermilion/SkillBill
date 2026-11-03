package skillbill.infrastructure.workflow.filesystem

import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.diffcomment.model.DiffCommentLineObservation
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileSystemDiffCommentWorktreeFilesTest {
  @Test
  fun `parent traversal and symlink escape are rejected`() {
    val root = Files.createTempDirectory("diff-comment-root")
    val outside = Files.createTempDirectory("diff-comment-outside")
    val externalFile = Files.writeString(outside.resolve("secret.txt"), "secret")
    Files.createSymbolicLink(root.resolve("escape.txt"), externalFile)
    val files = FileSystemDiffCommentWorktreeFiles(NoopDiagnostics)

    assertEquals(DiffCommentLineObservation.Rejected, files.lineText(root, "../secret.txt", 1))
    assertEquals(DiffCommentLineObservation.Rejected, files.lineText(root, "escape.txt", 1))
  }

  private object NoopDiagnostics : RuntimeDiagnostics {
    override fun warning(
      message: String,
      error: Throwable?,
    ) = Unit

    override fun error(
      message: String,
      error: Throwable?,
    ) = Unit

    override fun info(message: String) = Unit
  }
}
