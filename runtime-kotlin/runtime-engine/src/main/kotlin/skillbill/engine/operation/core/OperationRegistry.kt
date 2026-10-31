package skillbill.engine.operation.core

public class OperationRegistry(
  operations: List<Operation>,
) {
  private val byId: Map<String, Operation> =
    operations.fold(linkedMapOf()) { registered, operation ->
      require(registered.put(operation.id, operation) == null) {
        "Operation '${operation.id}' is registered more than once."
      }
      registered
    }

  public val ids: List<String> get() = byId.keys.toList()

  internal fun find(operationId: String): Operation? = byId[operationId]
}
