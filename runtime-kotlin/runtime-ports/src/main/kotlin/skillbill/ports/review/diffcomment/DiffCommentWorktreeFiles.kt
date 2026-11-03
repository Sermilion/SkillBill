package skillbill.ports.review.diffcomment

import skillbill.ports.review.diffcomment.model.DiffCommentLineObservation
import java.nio.file.Path

/** Reads a 1-based line from a validated path inside a worktree. */
interface DiffCommentWorktreeFiles {
  /**
   * Returns Text for an existing line, including Text("") for line 1 of an empty file,
   * Missing when absent or past EOF, and Rejected for unsafe paths.
   */
  fun lineText(
    worktreeRoot: Path,
    path: String,
    line: Int,
  ): DiffCommentLineObservation
}
