package skillbill.engine.work.observation

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.execution.core.asWorkerOwnership
import skillbill.engine.work.observation.model.WorkObservationRegistration
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.databaseMissing
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.taskruntime.WorkOwnerExitObserver
import java.time.Instant

@Inject
class WorkObservationService(
  private val database: DatabaseSessionFactory,
  private val exits: WorkOwnerExitObserver,
) {
  fun observeOwners(
    ids: List<String>,
    observedAt: Instant,
    onExit: () -> Unit,
  ): WorkObservationRegistration {
    val deadlines = mutableListOf<Instant>()
    val owners =
      database.readIfPresent { unit ->
        ids.flatMap { id ->
          val phase = unit.standalonePhaseStatuses.readByInvocation(id)
          val workflowId = phase?.workflowId ?: id
          val lease = unit.goalRunnerControls.controlState(workflowId).executionLease
          val worker = unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId)
          phase?.leaseExpiresAt?.let(deadlines::add)
          lease?.expiresAt?.let(deadlines::add)
          worker?.expiresAtInstant?.let(deadlines::add)
          listOfNotNull(
            phase?.workerOwnership(),
            lease?.asWorkerOwnership(workflowId),
            worker,
          )
        }.distinctBy { Triple(it.pid, it.processBirthToken, it.generation) }
      }
        ?: throw databaseMissing(database.resolveDbPath().toString(), DatabaseAccessOperation.READ)
    val registrations = mutableListOf<AutoCloseable>()
    runCatching {
      owners.forEach { exits.observe(it, onExit)?.let(registrations::add) }
    }.onFailure { failure ->
      registrations.forEach { it.close() }
      throw failure
    }
    return WorkObservationRegistration(deadlines.filter { it > observedAt }.minOrNull(), registrations)
  }
}
