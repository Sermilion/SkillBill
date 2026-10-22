package skillbill.engine.featuretask.phaserun.qa

import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.execution.testQaExecutionLifecycle
import skillbill.engine.featuretask.qa.report.QA_TEST_QA_ROOT
import skillbill.engine.featuretask.qa.report.QaReportSettlement
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaDeviceLease
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.ports.qa.model.QaLeaseResult
import skillbill.ports.qa.model.QaReportSource
import skillbill.ports.qa.model.QaWorkspace
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

internal class InMemoryQaWorkspaces(
  private val root: Path = Path.of(QA_TEST_QA_ROOT),
) : QaWorkspacePort {
  var report: String? = null
  val closedInvocations = mutableListOf<String>()
  val reports = mutableMapOf<Path, String>()
  private var current: QaWorkspace? = null

  override fun open(
    repoRoot: Path,
    invocationId: String,
  ): QaWorkspace {
    val directory = root.resolve(invocationId)
    return QaWorkspace(invocationId, directory, directory.resolve("report.md"), "$directory/")
      .also { current = it }
  }

  override fun readReport(reportPath: Path): QaReportSource? =
    (if (reportPath == current?.reportPath) report else reports[reportPath])?.let { content ->
      QaReportSource(content, "${reportPath.parent}/")
    }

  override fun readRetainedReport(
    repoRoot: Path,
    reference: String,
  ): QaReportSource? =
    reports[Path.of(reference)]?.let { content ->
      QaReportSource(content, "${Path.of(reference).parent}/")
    }

  override fun close(workspace: QaWorkspace) {
    report?.let { reports[workspace.reportPath] = it }
    closedInvocations += workspace.invocationId
  }
}

internal class RecordingQaLeases(
  private val outcomes: List<QaLeaseReleaseOutcome> = emptyList(),
) : QaDeviceLeasePort {
  val releasedInvocations = mutableListOf<String>()

  override fun acquire(
    repoRoot: Path,
    deviceFingerprint: String,
    invocationId: String,
  ): QaLeaseResult = error("The runtime never takes a device lease; the agent does.")

  override fun release(
    repoRoot: Path,
    lease: QaDeviceLease,
  ): QaLeaseReleaseOutcome = error("The runtime releases leases by invocation only.")

  override fun releaseAllFor(
    repoRoot: Path,
    invocationId: String,
  ): List<QaLeaseReleaseOutcome> {
    releasedInvocations += invocationId
    return outcomes
  }
}

internal fun qaStandalonePhaseRun(
  gitOperations: WorkflowGitOperations,
  manifests: List<PlatformManifest> = emptyList(),
  workspaces: QaWorkspacePort = InMemoryQaWorkspaces(),
  leases: QaDeviceLeasePort = RecordingQaLeases(),
): QaStandalonePhaseRun =
  QaStandalonePhaseRun(
    execution =
      testQaExecutionLifecycle(
        gitOperations,
        workspaces,
        leases,
        QaCapabilityResolver(gitOperations) {
          manifests
        },
      ),
    settlement = QaReportSettlement(workspaces, NoopRuntimeDiagnostics),
  )
