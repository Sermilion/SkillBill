package skillbill.ports.config

fun interface MachineConfigMigrationPort {
  fun migrateLegacyMachineConfig(): Boolean
}
