package skillbill.engine.operation.core

class OperationRegistry(
  operations: List<Operation>,
) {
  private val byId: Map<String, Operation> =
    operations.fold(linkedMapOf()) { registered, operation ->
      require(registered.put(operation.id, operation) == null) {
        "Operation '${operation.id}' is registered more than once."
      }
      registered
    }

  val ids: List<String> get() = byId.keys.toList()

  fun find(operationId: String): Operation? = byId[operationId]
}
