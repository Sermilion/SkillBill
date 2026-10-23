package skillbill.ports.blockincident

/**
 * Validates one canonical block incident document (contract_version, occurrence, aliases, state) against the
 * governed block incident schema. Implementations throw a typed INVALID_SCHEMA failure on any violation.
 */
fun interface BlockIncidentContractValidator {
  fun validate(document: String)
}
