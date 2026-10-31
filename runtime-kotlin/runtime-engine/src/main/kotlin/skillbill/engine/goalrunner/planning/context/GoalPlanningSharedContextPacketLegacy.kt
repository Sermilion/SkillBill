package skillbill.engine.goalrunner.planning.context

internal object GoalPlanningSharedContextPacketLegacy {
  fun migrateFromPacketVersion1(packet: Map<String, Any?>): Map<String, Any?> {
    if (packet.keys != GoalPlanningSharedContextPacket.LEGACY_V01_FIELDS) {
      invalidGoalPlanningSharedContextPacket(
        "<root>",
        "shared context packet fields are invalid for version '${GoalPlanningSharedContextPacket.LEGACY_VERSION_0_1}'",
      )
    }
    if (!GoalPlanningSharedContextPacketValidation.isStringMap(
        packet[GoalPlanningSharedContextPacketPayloadKeys.PLATFORM_PACKS],
      )
    ) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.PLATFORM_PACKS,
        "shared context platform packs are invalid for version '${GoalPlanningSharedContextPacket.LEGACY_VERSION_0_1}'",
      )
    }
    val integrityPayload = packet - GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256] !=
      GoalPlanningSharedContextPacketValidation.digest(integrityPayload)
    ) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256,
        "shared context packet integrity is invalid",
      )
    }
    val withoutLegacy = linkedMapOf<String, Any?>()
    for (field in GoalPlanningSharedContextPacket.PACKET_FIELDS) {
      if (field == GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION) {
        withoutLegacy[field] = GoalPlanningSharedContextPacket.LEGACY_VERSION_0_2
      } else if (field != GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256) {
        withoutLegacy[field] = packet.getValue(field)
      }
    }
    return withoutLegacy + (
      GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256 to
        GoalPlanningSharedContextPacketValidation.digest(withoutLegacy)
    )
  }

  fun migrateFromPacketVersion2(packet: Map<String, Any?>): Map<String, Any?> {
    if (packet.keys != GoalPlanningSharedContextPacket.PACKET_FIELDS) {
      invalidGoalPlanningSharedContextPacket(
        "<root>",
        "shared context packet fields are invalid for version '${GoalPlanningSharedContextPacket.LEGACY_VERSION_0_2}'",
      )
    }
    if (!GoalPlanningSharedContextPacketValidation.isStringMap(
        packet[GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY],
      )
    ) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY,
        "shared context boundary memory is invalid for version '${GoalPlanningSharedContextPacket.LEGACY_VERSION_0_2}'",
      )
    }
    val integrityPayload = packet - GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256] !=
      GoalPlanningSharedContextPacketValidation.digest(integrityPayload)
    ) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256,
        "shared context packet integrity is invalid",
      )
    }
    val migrated = linkedMapOf<String, Any?>()
    for (field in GoalPlanningSharedContextPacket.PACKET_FIELDS) {
      when (field) {
        GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION ->
          migrated[field] = GoalPlanningSharedContextPacket.LEGACY_VERSION_0_3
        GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256 -> Unit
        else -> migrated[field] = packet.getValue(field)
      }
    }
    return migrated + (
      GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256 to
        GoalPlanningSharedContextPacketValidation.digest(migrated)
    )
  }

  fun migrateFromPacketVersion3(packet: Map<String, Any?>): Map<String, Any?> {
    if (packet.keys != GoalPlanningSharedContextPacket.PACKET_FIELDS) {
      invalidGoalPlanningSharedContextPacket(
        "<root>",
        "shared context packet fields are invalid for version '${GoalPlanningSharedContextPacket.LEGACY_VERSION_0_3}'",
      )
    }
    if (!GoalPlanningSharedContextPacketValidation.isStringMap(
        packet[GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY],
      )
    ) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY,
        "shared context boundary memory is invalid for version '${GoalPlanningSharedContextPacket.LEGACY_VERSION_0_3}'",
      )
    }
    val integrityPayload = packet - GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256] !=
      GoalPlanningSharedContextPacketValidation.digest(integrityPayload)
    ) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256,
        "shared context packet integrity is invalid",
      )
    }
    val migrated = linkedMapOf<String, Any?>()
    for (field in GoalPlanningSharedContextPacket.PACKET_FIELDS) {
      when (field) {
        GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION ->
          migrated[field] = GoalPlanningSharedContextPacket.VERSION
        GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY ->
          migrated[field] = GoalPlanningSharedContextPacket.discardedCatalog()
        GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256 -> Unit
        else -> migrated[field] = packet.getValue(field)
      }
    }
    return migrated + (
      GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256 to
        GoalPlanningSharedContextPacketValidation.digest(migrated)
    )
  }
}
