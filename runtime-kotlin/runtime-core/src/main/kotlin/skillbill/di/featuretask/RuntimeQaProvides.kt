package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.contracts.qa.QaReceiptSchemaValidator
import skillbill.infrastructure.workflow.qa.FileSystemQaDeviceLeases
import skillbill.infrastructure.workflow.qa.FileSystemQaWorkspace
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.QaReceiptSchemaPort
import skillbill.ports.qa.QaWorkspacePort

internal interface RuntimeQaProvides {
  @Provides
  fun qaDeviceLeasePort(adapter: FileSystemQaDeviceLeases): QaDeviceLeasePort = adapter

  @Provides
  fun qaWorkspacePort(adapter: FileSystemQaWorkspace): QaWorkspacePort = adapter

  @Provides
  fun qaReceiptSchemaPort(): QaReceiptSchemaPort = QaReceiptSchemaValidator()
}
