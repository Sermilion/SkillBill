package skillbill.mcp.shared

import me.tatarka.inject.annotations.Component
import skillbill.application.learning.LearningService
import skillbill.application.review.service.ReviewService
import skillbill.application.system.SystemService
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.application.telemetry.service.TelemetryService
import skillbill.application.updatecheck.UpdateCheckService
import skillbill.application.workflow.service.WorkflowService
import skillbill.di.core.RuntimeComponent
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.workflow.WorkflowArtifactWireEncoder
import java.time.Clock

@Component
internal abstract class McpComponent(
  @Component val runtimeComponent: RuntimeComponent,
) {
  abstract val learningService: LearningService
  abstract val lifecycleTelemetryService: LifecycleTelemetryService
  abstract val telemetryService: TelemetryService
  abstract val reviewService: ReviewService
  abstract val systemService: SystemService
  abstract val workflowArtifactWireEncoder: WorkflowArtifactWireEncoder
  abstract val workflowService: WorkflowService
  abstract val updateCheckService: UpdateCheckService
  abstract val featureTaskPhaseSettlementService: FeatureTaskPhaseSettlementService
  abstract val scaffoldGateway: ScaffoldGateway
  abstract val resolvedEnvironmentContext: EnvironmentContext
  abstract val clock: Clock
  abstract val runtimeDiagnostics: RuntimeDiagnostics
}
