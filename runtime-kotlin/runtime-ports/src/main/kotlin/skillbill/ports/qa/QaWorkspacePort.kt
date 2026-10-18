package skillbill.ports.qa

import skillbill.ports.qa.model.QaReceiptSource
import skillbill.ports.qa.model.QaWorkspace
import java.nio.file.Path

/**
 * The per-invocation QA directory under the Git common directory. Generated scripts, evidence, and the receipt live
 * there and never in the working tree.
 */
interface QaWorkspacePort {
  /** Creates the directory of [invocationId] and records the current process as the invocation owner. */
  fun open(
    repoRoot: Path,
    invocationId: String,
  ): QaWorkspace

  /** The current or retained receipt and its owned evidence prefix, or null when no receipt exists. */
  fun readReceipt(receiptPath: Path): QaReceiptSource?

  /** Reads retained evidence only when [receiptReference] names a receipt owned by this repository. */
  fun readRetainedReceipt(
    qaRoot: Path,
    receiptReference: String,
  ): QaReceiptSource?

  /** Removes the invocation owner record of [workspace] so the finished invocation can no longer take a lease. */
  fun close(workspace: QaWorkspace)
}
