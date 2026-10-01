package skillbill.di.core

import skillbill.infrastructure.host.FileTelemetryConfigStore
import skillbill.model.EnvironmentContext
import skillbill.ports.telemetry.model.RemoteTransportResponse
import skillbill.ports.telemetry.transport.RemoteTransportPort
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class RuntimeComponentInvocationSnapshotTest {
  @Test
  fun `split home after first resolution keeps database and telemetry paths on the first home`() {
    val homeA = Files.createTempDirectory("skillbill-split-home-a").toAbsolutePath().normalize()
    val homeB = Files.createTempDirectory("skillbill-split-home-b").toAbsolutePath().normalize()
    val previousHome = System.getProperty("user.home")
    try {
      System.setProperty("user.home", homeA.toString())
      val component =
        RuntimeComponent::class.create(
          RuntimeContext(
            environment = EnvironmentContext(),
            transport = TransportContext(),
            workflowOps = WorkflowOpsContext(),
            callbacks = OptionalCallbacks(),
          ),
        )
      val environment = component.resolvedEnvironmentContext
      val firstDbPath =
        component.databaseSessionFactory(
          environment,
          Clock.systemUTC(),
          component.runtimeDiagnostics,
          NoOpWorkflowSnapshotValidator,
          component.runtimeVersion(),
        )
          .resolveDbPath().toAbsolutePath().normalize()
      val firstConfigPath =
        component.telemetryConfigStore(FileTelemetryConfigStore(environment))
          .configPath().toAbsolutePath().normalize()
      assertEquals(homeA, environment.userHome.toAbsolutePath().normalize())

      System.setProperty("user.home", homeB.toString())
      component.runtimeContext()
      val afterMutationEnvironment = component.resolvedEnvironmentContext
      val secondDbPath =
        component.databaseSessionFactory(
          afterMutationEnvironment,
          Clock.systemUTC(),
          component.runtimeDiagnostics,
          NoOpWorkflowSnapshotValidator,
          component.runtimeVersion(),
        ).resolveDbPath().toAbsolutePath().normalize()
      val secondConfigPath =
        component.telemetryConfigStore(FileTelemetryConfigStore(afterMutationEnvironment))
          .configPath().toAbsolutePath().normalize()

      assertEquals(homeA, afterMutationEnvironment.userHome.toAbsolutePath().normalize())
      assertEquals(firstDbPath, secondDbPath)
      assertEquals(firstConfigPath, secondConfigPath)

      val freshComponent =
        RuntimeComponent::class.create(
          RuntimeContext(
            environment = EnvironmentContext(),
            transport = TransportContext(),
            workflowOps = WorkflowOpsContext(),
            callbacks = OptionalCallbacks(),
          ),
        )
      assertEquals(
        homeB,
        freshComponent.resolvedEnvironmentContext.userHome.toAbsolutePath().normalize(),
      )
    } finally {
      if (previousHome == null) {
        System.clearProperty("user.home")
      } else {
        System.setProperty("user.home", previousHome)
      }
    }
  }

  @Test
  fun `non-default connect timeout reuses one component-selected requester`() {
    val home = Files.createTempDirectory("skillbill-requester-reuse")
    val component =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment = EnvironmentContext(userHome = home),
          transport = TransportContext(connectTimeout = Duration.ofMillis(750)),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )
    val first = component.runtimeContext().transport.requester
    component.telemetryService
    component.telemetryService
    val second = component.runtimeContext().transport.requester
    assertSame(first, second)
    component.runtimeContext()
    assertSame(first, component.runtimeContext().transport.requester)
  }

  @Test
  fun `caller-supplied requester stays the same instance across resolution`() {
    val home = Files.createTempDirectory("skillbill-supplied-requester")
    val supplied = RemoteTransportPort { _, _, _, _ -> RemoteTransportResponse(200, "") }
    val component =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment = EnvironmentContext(userHome = home),
          transport = TransportContext(requester = supplied),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )
    assertSame(supplied, component.runtimeContext().transport.requester)
    component.telemetryService
    assertSame(supplied, component.runtimeContext().transport.requester)
  }

  @Test
  fun `separate runtime components do not share scoped database or coordinator identity`() {
    val homeA = Files.createTempDirectory("skillbill-isolation-a")
    val homeB = Files.createTempDirectory("skillbill-isolation-b")
    val componentA =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment = EnvironmentContext(environment = emptyMap(), userHome = homeA),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )
    val componentB =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment = EnvironmentContext(environment = emptyMap(), userHome = homeB),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )
    assertNotSame(
      componentA.featureTaskRuntimeWorkerCoordinator,
      componentB.featureTaskRuntimeWorkerCoordinator,
    )
    assertNotSame(
      componentA.databaseSessionFactory(
        componentA.resolvedEnvironmentContext,
        Clock.systemUTC(),
        componentA.runtimeDiagnostics,
        NoOpWorkflowSnapshotValidator,
        componentA.runtimeVersion(),
      ),
      componentB.databaseSessionFactory(
        componentB.resolvedEnvironmentContext,
        Clock.systemUTC(),
        componentB.runtimeDiagnostics,
        NoOpWorkflowSnapshotValidator,
        componentB.runtimeVersion(),
      ),
    )
  }

  private object NoOpWorkflowSnapshotValidator : WorkflowSnapshotValidator {
    override fun validate(
      snapshot: WorkflowStateSnapshot,
      slug: String,
    ) = Unit
  }
}
