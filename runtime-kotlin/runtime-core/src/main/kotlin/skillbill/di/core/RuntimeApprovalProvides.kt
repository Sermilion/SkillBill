package skillbill.di.core

import me.tatarka.inject.annotations.Provides
import skillbill.application.approval.BrokerApprovalOwnerAuthority
import skillbill.infrastructure.host.scheduler.JdkApprovalDeadlineScheduler
import skillbill.ports.approval.ApprovalDeadlineScheduler
import skillbill.ports.approval.ApprovalOwnerAuthority

internal interface RuntimeApprovalProvides {
  @Provides
  fun approvalDeadlineScheduler(adapter: JdkApprovalDeadlineScheduler): ApprovalDeadlineScheduler = adapter

  @Provides
  fun approvalOwnerAuthority(adapter: BrokerApprovalOwnerAuthority): ApprovalOwnerAuthority = adapter
}
