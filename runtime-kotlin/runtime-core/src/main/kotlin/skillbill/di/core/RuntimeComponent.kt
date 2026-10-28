package skillbill.di.core

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import skillbill.application.agentrun.AgentRunService
import skillbill.application.config.ConfigResolutionService
import skillbill.application.install.ExternalAddonOverlayService
import skillbill.application.install.ExternalPlatformPackResolutionService
import skillbill.application.install.InstallService
import skillbill.application.learning.LearningService
import skillbill.application.review.service.ReviewService
import skillbill.application.review.snapshot.ReviewSnapshotPruneService
import skillbill.application.runtime.RuntimeSingleton
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.scaffold.SkillRemove
import skillbill.application.system.SystemService
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.application.telemetry.service.BlockIncidentTelemetry
import skillbill.application.telemetry.service.TelemetryService
import skillbill.application.uninstall.SkillBillUninstallService
import skillbill.application.updatecheck.SkillBillUpdateService
import skillbill.application.updatecheck.UpdateCheckService
import skillbill.application.work.WorkListService
import skillbill.application.workflow.service.WorkflowService
import skillbill.di.featurespec.RuntimeFeatureSpecProvides
import skillbill.di.featuretask.RuntimeFeatureTaskProvides
import skillbill.di.featuretask.RuntimeFeatureTaskSlotProvides
import skillbill.di.featuretask.RuntimeFeatureTaskValidatorProvides
import skillbill.di.featuretask.RuntimeQaProvides
import skillbill.di.featuretask.RuntimeStandaloneMonitorProvides
import skillbill.di.goal.RuntimeGoalPlanningProvides
import skillbill.di.goal.RuntimeGoalRunnerLaunchProvides
import skillbill.di.goal.RuntimeGoalRunnerStoreProvides
import skillbill.di.install.RuntimeExternalPlatformPackProvides
import skillbill.di.install.RuntimeInstallPlanProvides
import skillbill.di.install.RuntimeInstallTargetProvides
import skillbill.di.install.RuntimeInstallerProvides
import skillbill.di.operation.RuntimeOperationProvides
import skillbill.di.review.RuntimeReviewAddonCatalogProvides
import skillbill.di.review.RuntimeReviewEvidenceProvides
import skillbill.di.review.RuntimeReviewLaunchProvides
import skillbill.di.scaffold.RuntimeScaffoldProvides
import skillbill.di.scaffold.RuntimeScaffoldValidationProvides
import skillbill.di.telemetry.RuntimeTelemetryProvides
import skillbill.di.workflow.RuntimeWorkflowProvides
import skillbill.di.workflow.RuntimeWorkflowValidatorProvides
import skillbill.engine.diagnostics.block.BlockIncidentCapture
import skillbill.engine.diagnostics.block.BlockIncidentInvestigator
import skillbill.engine.diagnostics.block.investigation.EvidenceBlockIncidentInvestigator
import skillbill.engine.diagnostics.block.report.BlockIncidentLaunchFactsReader
import skillbill.engine.diagnostics.block.report.ProductReportDelivery
import skillbill.engine.diagnostics.block.report.ProductReportScheduler
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeWorkerCoordinator
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.StandalonePhaseRegistrationFactory
import skillbill.engine.featuretask.phaserun.StandalonePhaseStatusPublisherFactory
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeRunEntry
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.goalrunner.GoalOperatorDecisionService
import skillbill.engine.goalrunner.GoalRunner
import skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.engine.goalrunner.plan.StandalonePlanRun
import skillbill.engine.goalrunner.planning.GoalPlanningLogService
import skillbill.engine.goalrunner.preflight.GoalPreflightService
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.engine.operation.core.OperationExecutor
import skillbill.engine.operation.core.OperationRegistry
import skillbill.engine.specbrowser.SpecBrowserService
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.WorkBoardReadModelProjection
import skillbill.engine.work.control.WorkControlService
import skillbill.engine.work.observation.WorkObservationService
import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.infrastructure.host.blockincident.FileProductReportDeliveryOwnership
import skillbill.infrastructure.host.concurrency.JvmInterruptSignalPort
import skillbill.infrastructure.host.process.EnvironmentGhProductIssueCredential
import skillbill.infrastructure.host.projectstore.ProjectStoreResolver
import skillbill.infrastructure.host.system.FileSystemProjectConfigFile
import skillbill.infrastructure.host.system.PackagedRuntimeBuildIdentity
import skillbill.infrastructure.host.system.ProcessFileEditorLauncher
import skillbill.infrastructure.http.productissue.GitHubProductIssueAdapter
import skillbill.infrastructure.sqlite.SQLiteDatabaseSchema
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.infrastructure.workflow.git.lineage.GitRepositoryLineage
import skillbill.model.EnvironmentContext
import skillbill.model.RepositoryRoot
import skillbill.model.RuntimeVersion
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.blockincident.BlockIncidentContractValidator
import skillbill.ports.blockincident.BlockIncidentTelemetryPort
import skillbill.ports.blockincident.ProductIssueCredentialPort
import skillbill.ports.blockincident.ProductIssueTrackerPort
import skillbill.ports.blockincident.ProductReportDeliveryOwnershipPort
import skillbill.ports.concurrency.InterruptSignalPort
import skillbill.ports.config.ProjectConfigFilePort
import skillbill.ports.db.DatabaseReadConnections
import skillbill.ports.db.DatabaseSchemaPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.editor.FileEditorLauncherPort
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.idestatus.WorkBoardReadModelProjector
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.projectstore.ProjectCatalogPort
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.RepositoryLineagePort
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.QaOwnedResourcePort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.system.RuntimeBuildIdentityPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.telemetry.transport.RemoteTransportPort
import skillbill.ports.telemetry.transport.TelemetryLevelMutator
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.workflow.WorkflowSnapshotValidator
import java.time.Clock

@RuntimeSingleton
@Component
abstract class RuntimeComponent(
  private val inputRuntimeContext: RuntimeContext,
) :
  RuntimeInstallTargetProvides,
    RuntimeInstallPlanProvides,
    RuntimeExternalPlatformPackProvides,
    RuntimeTelemetryProvides,
    RuntimeGoalPlanningProvides,
    RuntimeGoalRunnerStoreProvides,
    RuntimeGoalRunnerLaunchProvides,
    RuntimeReviewLaunchProvides,
    RuntimeReviewAddonCatalogProvides,
    RuntimeReviewEvidenceProvides,
    RuntimeFeatureTaskProvides,
    RuntimeFeatureTaskSlotProvides,
    RuntimeStandaloneMonitorProvides,
    RuntimeQaProvides,
    RuntimeOperationProvides,
    RuntimeFeatureSpecProvides,
    RuntimeWorkflowProvides,
    RuntimeWorkflowValidatorProvides,
    RuntimeFeatureTaskValidatorProvides,
    RuntimeScaffoldProvides,
    RuntimeScaffoldValidationProvides,
    RuntimeInstallerProvides,
    RuntimeOptionalCallbackProvides,
    RuntimeWorkControlProvides,
    RuntimeDiagnosticsProvides {
  private val resolvedRuntimeContext: RuntimeContext by lazy {
    RuntimeBootstrapBindings.runtimeContext(inputRuntimeContext)
  }

  @Provides
  fun runtimeContext(): RuntimeContext = resolvedRuntimeContext

  @Provides
  fun environmentContext(ctx: RuntimeContext): EnvironmentContext = ctx.environment

  @Provides
  fun transportContext(ctx: RuntimeContext): TransportContext = ctx.transport

  @Provides
  fun remoteTransportPort(ctx: TransportContext): RemoteTransportPort =
    ctx.requester
      ?: error("RemoteTransportPort is unresolved; provide it from the composition root after bootstrap resolution.")

  @Provides
  fun productIssueTrackerPort(adapter: GitHubProductIssueAdapter): ProductIssueTrackerPort = adapter

  @Provides
  fun productIssueCredentialPort(adapter: EnvironmentGhProductIssueCredential): ProductIssueCredentialPort = adapter

  @Provides
  fun productReportDeliveryOwnershipPort(
    adapter: FileProductReportDeliveryOwnership,
  ): ProductReportDeliveryOwnershipPort = adapter

  @Provides
  fun runtimeBuildIdentityPort(adapter: PackagedRuntimeBuildIdentity): RuntimeBuildIdentityPort = adapter

  @Provides
  fun blockIncidentTelemetryPort(adapter: BlockIncidentTelemetry): BlockIncidentTelemetryPort = adapter

  @Provides
  fun optionalBlockIncidentTelemetry(adapter: BlockIncidentTelemetryPort): BlockIncidentTelemetryPort? = adapter

  @Provides
  fun optionalProductReportScheduler(scheduler: ProductReportScheduler): ProductReportScheduler? = scheduler

  @Provides
  fun optionalProductReportDelivery(delivery: ProductReportDelivery): ProductReportDelivery? = delivery

  @Provides
  fun optionalBlockIncidentLaunchFacts(reader: BlockIncidentLaunchFactsReader): BlockIncidentLaunchFactsReader? = reader

  @Provides
  fun workflowOpsContext(ctx: RuntimeContext): WorkflowOpsContext = ctx.workflowOps

  @Provides
  fun repositoryRoot(context: EnvironmentContext): RepositoryRoot = RepositoryRoot(context.repositoryRoot)

  @Provides
  fun runtimeVersion(): RuntimeVersion = RuntimeVersion(SkillBillVersion.VALUE)

  @Provides
  fun blockIncidentInvestigator(investigator: EvidenceBlockIncidentInvestigator): BlockIncidentInvestigator =
    investigator

  @Provides
  fun optionalCallbacks(ctx: RuntimeContext): OptionalCallbacks = ctx.callbacks

  @Provides
  fun repositoryEnclosingRootPort(): RepositoryEnclosingRootPort = CanonicalRepositoryRoot

  @Provides
  fun repositoryLineagePort(adapter: GitRepositoryLineage): RepositoryLineagePort = adapter

  @Provides @RuntimeSingleton
  fun projectStorePort(resolver: ProjectStoreResolver): ProjectStorePort = resolver

  @Provides @RuntimeSingleton
  fun projectCatalogPort(resolver: ProjectStoreResolver): ProjectCatalogPort = resolver

  @Provides
  fun projectConfigFilePort(adapter: FileSystemProjectConfigFile): ProjectConfigFilePort = adapter

  @Provides
  fun fileEditorLauncherPort(adapter: ProcessFileEditorLauncher): FileEditorLauncherPort = adapter

  @Provides @RuntimeSingleton
  internal fun sqliteDatabaseSessionFactory(
    context: EnvironmentContext,
    clock: Clock,
    diagnostics: RuntimeDiagnostics,
    workflowSnapshotValidator: WorkflowSnapshotValidator,
    blockIncidentContractValidator: BlockIncidentContractValidator,
  ): SQLiteDatabaseSessionFactory =
    SQLiteDatabaseSessionFactory(
      context,
      clock,
      diagnostics,
      workflowSnapshotValidator,
      runtimeVersion().value,
      blockIncidentContractValidator,
      lazy { boardReadModelProjector() },
    )

  @Provides @RuntimeSingleton
  fun workBoardReadModelProjector(impl: WorkBoardReadModelProjection): WorkBoardReadModelProjector = impl

  internal abstract fun boardReadModelProjector(): WorkBoardReadModelProjector

  @Provides
  fun databaseSessionFactory(factory: SQLiteDatabaseSessionFactory): DatabaseSessionFactory = factory

  @Provides
  fun databaseReadConnections(factory: SQLiteDatabaseSessionFactory): DatabaseReadConnections = factory

  @Provides
  fun databaseSchemaPort(adapter: SQLiteDatabaseSchema): DatabaseSchemaPort = adapter

  @Provides
  fun standalonePhaseStatusPublisherFactory(
    database: DatabaseSessionFactory,
    registrations: StandalonePhaseRegistrationFactory,
    clock: Clock,
    diagnostics: RuntimeDiagnostics,
    blockIncidentCapture: BlockIncidentCapture,
  ): StandalonePhaseStatusPublisherFactory =
    StandalonePhaseStatusPublisherFactory(database, registrations, clock, diagnostics, blockIncidentCapture)

  @Provides
  fun interruptSignal(): InterruptSignalPort = JvmInterruptSignalPort

  abstract val resolvedEnvironmentContext: EnvironmentContext

  abstract val repositoryEnclosingRootPort: RepositoryEnclosingRootPort
  abstract val projectStorePort: ProjectStorePort
  abstract val projectConfigFilePort: ProjectConfigFilePort
  abstract val fileEditorLauncherPort: FileEditorLauncherPort
  abstract val checkedOutBranchSource: CheckedOutBranchSource

  abstract val featureTaskContinuationLookupService: FeatureTaskContinuationLookupService
  abstract val featureTaskPhaseSettlementService: FeatureTaskPhaseSettlementService
  abstract val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService

  abstract val phaseRunEntry: PhaseRunEntry
  abstract val qaDeviceLeasePort: QaDeviceLeasePort
  abstract val qaOwnedResourcePort: QaOwnedResourcePort
  abstract val phaseStrategyLookup: PhaseStrategyLookup
  abstract val operationExecutor: OperationExecutor
  abstract val operationRegistry: OperationRegistry
  abstract val configResolutionService: ConfigResolutionService
  abstract val externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort
  abstract val installService: InstallService
  abstract val externalAddonOverlayService: ExternalAddonOverlayService
  abstract val externalPlatformPackResolutionService: ExternalPlatformPackResolutionService
  abstract val agentRunLauncher: AgentRunLauncher
  abstract val agentRunService: AgentRunService
  abstract val featureTaskRuntimeRunEntry: FeatureTaskRuntimeRunEntry

  abstract val featureTaskRuntimeCrashReconciler: FeatureTaskRuntimeCrashReconciler
  abstract val featureTaskRuntimeExecutionPlanResolver: FeatureTaskRuntimeExecutionPlanResolver
  abstract val featureTaskRuntimeStatusService: FeatureTaskRuntimeStatusService
  abstract val featureTaskRuntimeWorkerCoordinator: FeatureTaskRuntimeWorkerCoordinator
  abstract val featureTaskRuntimeRunInvariantsSource: FeatureTaskRuntimeRunInvariantsSource
  abstract val featureSpecPathResolverPort: FeatureSpecPathResolverPort
  abstract val goalRunner: GoalRunner
  abstract val standalonePlanRun: StandalonePlanRun
  abstract val goalPreflightService: GoalPreflightService
  abstract val goalRunnerStatusService: GoalRunnerStatusService
  abstract val goalPlanningLogService: GoalPlanningLogService
  abstract val goalOperatorDecisionService: GoalOperatorDecisionService
  abstract val installAgentService: InstallAgentService
  abstract val installMcpRegistrationPort: InstallMcpRegistrationPort
  abstract val installNativeAgentLinkPort: InstallNativeAgentLinkPort
  abstract val installSelectionPersistencePort: InstallSelectionPersistencePort
  abstract val learningService: LearningService
  abstract val lifecycleTelemetryService: LifecycleTelemetryService
  abstract val repoValidationGateway: RepoValidationGateway
  abstract val reviewService: ReviewService
  abstract val reviewSnapshotPruneService: ReviewSnapshotPruneService
  abstract val runtimeDiagnostics: RuntimeDiagnostics
  abstract val scaffoldCatalogGateway: ScaffoldCatalogGateway
  abstract val scaffoldGateway: ScaffoldGateway
  abstract val skillRemove: SkillRemove
  abstract val systemService: SystemService
  abstract val skillBillUpdateService: SkillBillUpdateService
  abstract val updateCheckService: UpdateCheckService
  abstract val skillBillUninstallService: SkillBillUninstallService
  abstract val telemetryLevelMutator: TelemetryLevelMutator
  abstract val telemetryService: TelemetryService
  abstract val workflowService: WorkflowService
  abstract val workListService: WorkListService
  abstract val workControlService: WorkControlService
  abstract val specBrowserService: SpecBrowserService

  abstract val workObservationService: WorkObservationService

  abstract val ideStatusService: IdeStatusService
  abstract val databaseSession: DatabaseSessionFactory
  abstract val databaseReadConnections: DatabaseReadConnections
  abstract val databaseSchema: DatabaseSchemaPort
}
