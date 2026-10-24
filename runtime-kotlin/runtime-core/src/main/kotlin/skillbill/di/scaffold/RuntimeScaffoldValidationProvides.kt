package skillbill.di.scaffold

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.host.system.FileSystemRepoLocalConfig
import skillbill.infrastructure.skills.scaffold.FileSystemRepoValidationGateway
import skillbill.infrastructure.workflow.validation.FileSystemValidationGateRunner
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.validation.ValidationGateRunner

internal interface RuntimeScaffoldValidationProvides {
  @Provides
  fun repoLocalConfigPort(
    diagnostics: RuntimeDiagnostics,
    projectStore: ProjectStorePort,
  ): RepoLocalConfigPort = FileSystemRepoLocalConfig(diagnostics, projectStore)

  @Provides
  fun repoValidationGateway(gateway: FileSystemRepoValidationGateway): RepoValidationGateway = gateway

  @Provides
  fun validationGateRunner(runner: FileSystemValidationGateRunner): ValidationGateRunner = runner
}
