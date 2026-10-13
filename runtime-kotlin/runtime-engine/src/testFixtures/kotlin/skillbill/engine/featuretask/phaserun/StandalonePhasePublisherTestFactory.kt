package skillbill.engine.featuretask.phaserun

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import java.time.Clock

fun testStandalonePhasePublisher(
  database: DatabaseSessionFactory,
  clock: Clock,
  diagnostics: RuntimeDiagnostics,
): StandalonePhaseStatusPublisherFactory =
  StandalonePhaseStatusPublisherFactory(
    database,
    StandalonePhaseRegistrationFactory(
      TestRepositoryEnclosingRoot,
      CheckedOutBranchSource { "main" },
      clock,
      NoopFeatureTaskRuntimeWorkerSupervisor,
      diagnostics,
    ),
    clock,
    diagnostics,
  )
