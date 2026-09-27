package skillbill.di.operation

import me.tatarka.inject.annotations.Provides
import skillbill.application.updatecheck.UpdateCheckService
import skillbill.engine.operation.core.OperationRegistry
import skillbill.engine.operation.release.ReleaseOperation
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
  ): OperationRegistry =
    OperationRegistry(
      listOf(
        UpdateCheckOperation(updateCheckService),
        ReleaseOperation(gitOperations),
      ),
    )

  @Provides
  fun operationProposalRepository(database: DatabaseSessionFactory): OperationProposalRepository =
    SqliteOperationProposalRepository(database)
}
