package skillbill.infrastructure.workflow.goalworktree

import skillbill.goalrunner.model.GoalPlanningCheckoutRecovery
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.DatabasePathProtectionResult
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.goalrunner.EmptyGoalPlanningOwnershipRepository
import skillbill.ports.goalrunner.GoalPlanningOwnershipRepository
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.persistence.UnitOfWork
import java.lang.reflect.Proxy
import java.nio.file.Path

internal class WorktreeTestDatabase : DatabaseSessionFactory {
  val owners = mutableMapOf<Path, GoalPlanningOwnership>()
  val recoveries = mutableMapOf<String, GoalPlanningCheckoutRecovery>()
  private val consumed = mutableSetOf<String>()
  private val ownership =
    object : GoalPlanningOwnershipRepository by EmptyGoalPlanningOwnershipRepository {
      override fun findByWorkflow(workflowId: String): GoalPlanningOwnership? =
        owners.values.singleOrNull { it.workflowId == workflowId }

      override fun checkoutRecovery(ownership: GoalPlanningOwnership): GoalPlanningCheckoutRecovery? =
        recoveries[ownership.reservationId]

      override fun isConsumedCheckoutRecovery(
        ownership: GoalPlanningOwnership,
        generation: String,
      ): Boolean = generation in consumed

      override fun advanceCheckoutRecovery(
        ownership: GoalPlanningOwnership,
        recovery: GoalPlanningCheckoutRecovery,
        nextState: GoalPlanningCheckoutRecovery.State,
        replacementGitIdentity: String?,
      ): Boolean {
        val current = recoveries[ownership.reservationId]
        if (current == null) {
          if (nextState != GoalPlanningCheckoutRecovery.State.FENCED) return false
        } else if (current != recovery || !recovery.permits(nextState, replacementGitIdentity)) {
          return false
        }
        if (nextState == GoalPlanningCheckoutRecovery.State.CONSUMED) {
          recoveries.remove(ownership.reservationId)
          consumed.add(recovery.generation)
        } else {
          recoveries[ownership.reservationId] = recovery.copy(state = nextState)
        }
        return true
      }
    }
  private val unit =
    Proxy.newProxyInstance(UnitOfWork::class.java.classLoader, arrayOf(UnitOfWork::class.java)) { _, method, _ ->
      if (method.name == "getGoalPlanningOwnership") {
        ownership
      } else {
        error(
          "Unexpected unit of work access: ${method.name}",
        )
      }
    } as UnitOfWork

  override fun acquireDatabasePathProtection(): DatabasePathProtectionResult =
    DatabasePathProtectionResult.Acquired(AutoCloseable {})

  override fun resolveDbPath(): Path = Path.of("test.db")

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unit)

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unit)

  override fun <T> owningTransaction(block: (UnitOfWork) -> T): OwningTransactionResult<T> =
    OwningTransactionResult.Committed(block(unit))

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = block(unit)
}
