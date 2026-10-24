package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.workflow.qa.FileSystemQaDeviceLeases
import skillbill.infrastructure.workflow.qa.FileSystemQaOwnedResources
import skillbill.infrastructure.workflow.qa.FileSystemQaWorkspace
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.QaOwnedResourcePort
import skillbill.ports.qa.QaWorkspacePort

internal interface RuntimeQaProvides {
  @Provides
  fun qaDeviceLeasePort(adapter: FileSystemQaDeviceLeases): QaDeviceLeasePort = adapter

  @Provides
  fun qaOwnedResourcePort(adapter: FileSystemQaOwnedResources): QaOwnedResourcePort = adapter

  @Provides
  fun qaWorkspacePort(adapter: FileSystemQaWorkspace): QaWorkspacePort = adapter
}
