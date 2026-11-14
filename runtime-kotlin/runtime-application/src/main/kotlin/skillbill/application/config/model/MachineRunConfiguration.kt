package skillbill.application.config.model

import skillbill.config.model.CompactionSettings
import skillbill.config.model.ExecutionMatrix

data class MachineRunConfiguration(
  val executionMatrix: ExecutionMatrix?,
  val compactionSettings: CompactionSettings,
)
