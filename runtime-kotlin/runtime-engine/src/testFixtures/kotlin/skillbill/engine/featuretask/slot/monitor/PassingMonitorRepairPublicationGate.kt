package skillbill.engine.featuretask.slot.monitor

val PassingMonitorRepairPublicationGate: MonitorRepairPublicationGate =
  MonitorRepairPublicationGate { _, _ -> MonitorRepairPublicationVerdict.Publishable }
