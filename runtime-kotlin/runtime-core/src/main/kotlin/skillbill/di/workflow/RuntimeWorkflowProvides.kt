package skillbill.di.workflow

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.workflow.decomposition.FileSystemDecompositionManifestFileStore
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore

internal interface RuntimeWorkflowProvides {
  @Provides
  fun decompositionManifestStore(projectStore: ProjectStorePort): DecompositionManifestStore =
    FileSystemDecompositionManifestFileStore(projectStore)
}
