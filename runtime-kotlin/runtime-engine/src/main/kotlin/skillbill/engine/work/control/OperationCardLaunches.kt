package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.operationcard.OperationCardOperationIds
import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.contracts.operationcard.OperationCardStatus
import skillbill.contracts.operationcard.OperationLaunchContext
import skillbill.engine.operation.core.OperationCardLifecycle
import skillbill.engine.operation.core.OperationCardRevalidation
import skillbill.ports.operation.OperationCardRepository
import skillbill.ports.work.LaunchAgentHomesPort
import java.util.UUID

internal sealed interface CardAdmission {
  data class Admitted(
    val card: OperationCardProjection,
    val launch: OperationLaunchContext,
    val executionId: Long,
  ) : CardAdmission

  data class Refused(val reason: String) : CardAdmission
}

@Inject
public class OperationCardLaunches(
  private val cards: OperationCardRepository,
  private val lifecycle: OperationCardLifecycle,
  private val homes: LaunchAgentHomesPort,
) {
  internal fun agentHomes(): Map<String, String> = homes.agentHomes()

  internal fun tracks(operationId: String): Boolean = operationId in OperationCardOperationIds.ALL

  internal fun newLaunchId(): String = "$LAUNCH_PREFIX${UUID.randomUUID()}"

  internal fun holderId(
    operationId: String,
    launchId: String,
  ): String = "$operationId:$launchId"

  internal fun save(context: OperationLaunchContext) {
    cards.saveLaunchContext(context.copy(databaseIdentity = cards.databaseIdentity()))
  }

  internal fun admit(
    token: String,
    operationId: String,
    selection: String?,
  ): CardAdmission {
    val card = cards.find(token) ?: return CardAdmission.Refused("No operation card has token '$token'.")
    val launch = cards.findLaunchContext(card.launchId)
    val status = card.status.wireValue
    val refusal =
      when {
        card.operationId != operationId -> "Operation card '$token' belongs to another operation."
        card.status != OperationCardStatus.AWAITING_CONFIRMATION -> "Operation card '$token' is $status."
        card.databaseIdentity != cards.databaseIdentity() -> "Operation card '$token' belongs to another database."
        launch == null -> "Operation card '$token' has no launch context."
        else -> null
      }
    if (refusal != null || launch == null) return CardAdmission.Refused(refusal.orEmpty())
    val execution =
      lifecycle.admit(token, selection) ?: return CardAdmission.Refused("Operation card '$token' changed.")
    return CardAdmission.Admitted(card, launch, execution.executionId)
  }

  internal fun revalidate(token: String): OperationCardRevalidation =
    lifecycle.revalidate(token, cards.databaseIdentity())

  internal fun dismiss(token: String): Pair<Boolean, OperationLaunchContext?> {
    val card = cards.find(token)
    val dismissed = lifecycle.dismiss(token)
    return dismissed to card?.let { cards.findLaunchContext(it.launchId) }
  }

  internal fun withdraw(card: OperationCardProjection) {
    lifecycle.withdraw(card.operationId, card.token)
  }

  internal fun childEnded(
    token: String,
    executionId: Long,
  ) {
    lifecycle.childEnded(token, executionId)
  }

  internal fun find(token: String): OperationCardProjection? = cards.find(token)

  internal fun findByLaunch(launchId: String): OperationCardProjection? = cards.findByLaunch(launchId)

  private companion object {
    const val LAUNCH_PREFIX = "opl-"
  }
}
