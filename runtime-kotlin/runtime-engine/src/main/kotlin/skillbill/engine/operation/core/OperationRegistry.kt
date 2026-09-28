package skillbill.engine.operation.core

import skillbill.error.operation.DuplicateOperationIdError
import skillbill.error.operation.UnknownOperationIdError

class OperationRegistry(
  operations: List<Operation>,
) {
  private val byId: Map<String, Operation> =
    operations.fold(linkedMapOf()) { registered, operation ->
      if (registered.put(operation.id, operation) != null) throw DuplicateOperationIdError(operation.id)
      registered
    }

  val ids: List<String> get() = byId.keys.toList()

  fun get(operationId: String): Operation = byId[operationId] ?: throw UnknownOperationIdError(operationId, ids)
}
