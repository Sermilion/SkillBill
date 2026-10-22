package skillbill.engine.featuretask.phaserun

import skillbill.engine.diagnostics.block.testBlockIncidentCapture
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import java.time.Clock

fun testStandalonePhasePublisher(
  database: DatabaseSessionFactory,
  clock: Clock,
  diagnostics: RuntimeDiagnostics,
  environment: EnvironmentContext = EnvironmentContext(environment = emptyMap()),
): StandalonePhaseStatusPublisherFactory =
  StandalonePhaseStatusPublisherFactory(
    database,
    StandalonePhaseRegistrationFactory(
      TestRepositoryEnclosingRoot,
      CheckedOutBranchSource { "main" },
      clock,
      NoopFeatureTaskRuntimeWorkerSupervisor,
      diagnostics,
      environment,
    ),
    clock,
    diagnostics,
    testBlockIncidentCapture(database, clock, diagnostics),
  )
