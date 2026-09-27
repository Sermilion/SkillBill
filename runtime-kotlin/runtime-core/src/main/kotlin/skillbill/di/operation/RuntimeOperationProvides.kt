package skillbill.di.operation

import me.tatarka.inject.annotations.Provides
import skillbill.application.updatecheck.UpdateCheckService
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.operation.core.OperationRegistry
import skillbill.engine.operation.featureguard.FeatureGuardOperation
import skillbill.engine.operation.featureguardcleanup.FeatureGuardCleanupOperation
import skillbill.engine.operation.release.ReleaseOperation
import skillbill.engine.operation.unittestvalue.UnitTestValueCheckOperation
import skillbill.engine.operation.updatecheck.UpdateCheckOperation
import skillbill.infrastructure.sqlite.operation.SqliteOperationProposalRepository
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations

internal interface RuntimeOperationProvides {
  @Provides
  fun operationRegistry(
    updateCheckService: UpdateCheckService,
    gitOperations: WorkflowGitOperations,
    phaseRunEntry: PhaseRunEntry,
  ): OperationRegistry =
    OperationRegistry(
      listOf(
        UpdateCheckOperation(updateCheckService),
        ReleaseOperation(gitOperations),
        UnitTestValueCheckOperation(gitOperations),
        FeatureGuardOperation(),
        FeatureGuardCleanupOperation(phaseRunEntry::run),
      ),
    )

  @Provides
  fun operationProposalRepository(database: DatabaseSessionFactory): OperationProposalRepository =
    SqliteOperationProposalRepository(database)
}
