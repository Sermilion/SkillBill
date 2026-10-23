package skillbill.ports.qa

import skillbill.ports.qa.model.QaReportSource
import skillbill.ports.qa.model.QaWorkspace
import java.nio.file.Path

/**
 * The per-invocation QA directory under the Git common directory. Generated scripts, evidence, and the report live
 * there and never in the working tree.
 */
interface QaWorkspacePort {
  /** Creates the directory of [invocationId] and records the current process as the invocation owner. */
  fun open(
    repoRoot: Path,
    invocationId: String,
  ): QaWorkspace

  /** The report at [reportPath] and its owned evidence prefix, or null when no report exists. */
  fun readReport(reportPath: Path): QaReportSource?

  /**
   * A retained report or legacy receipt reference anchored to [repoRoot] and its owned evidence prefix,
   * or null when unavailable.
   */
  fun readRetainedReport(
    repoRoot: Path,
    reference: String,
  ): QaReportSource?

  /** Removes the invocation owner record of [workspace] so the finished invocation can no longer take a lease. */
  fun close(workspace: QaWorkspace)
}
