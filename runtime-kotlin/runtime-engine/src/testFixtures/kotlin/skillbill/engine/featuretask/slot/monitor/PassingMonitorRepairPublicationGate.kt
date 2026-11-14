package skillbill.engine.featuretask.slot.monitor

val PassingMonitorRepairPublicationGate: MonitorRepairPublicationGate =
  MonitorRepairPublicationGate { MonitorRepairPublicationVerdict.Publishable }
