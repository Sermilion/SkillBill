package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.host.process.JvmAppRunProcessRunner
import skillbill.infrastructure.workflow.qa.FileSystemQaChecklistPlanFile
import skillbill.infrastructure.workflow.qa.FileSystemQaDeviceLeases
import skillbill.infrastructure.workflow.qa.FileSystemQaOwnedResources
import skillbill.infrastructure.workflow.qa.FileSystemQaWorkspace
import skillbill.ports.apprun.AppRunProcessPort
import skillbill.ports.qa.QaChecklistPlanFile
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.QaOwnedResourcePort
import skillbill.ports.qa.QaWorkspacePort

internal interface RuntimeQaProvides {
  @Provides
  fun appRunProcessPort(adapter: JvmAppRunProcessRunner): AppRunProcessPort = adapter

  @Provides
  fun qaChecklistPlanFile(): QaChecklistPlanFile = FileSystemQaChecklistPlanFile()

  @Provides
  fun qaDeviceLeasePort(adapter: FileSystemQaDeviceLeases): QaDeviceLeasePort = adapter

  @Provides
  fun qaOwnedResourcePort(adapter: FileSystemQaOwnedResources): QaOwnedResourcePort = adapter

  @Provides
  fun qaWorkspacePort(adapter: FileSystemQaWorkspace): QaWorkspacePort = adapter
}
