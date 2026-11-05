package dev.skillbill.desktop.core.data.approval

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.data.di.DatabaseSessionScope
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** One live approval owner generation that must end, cancelling its requests, before its database session is released. */
fun interface ApprovalGeneration {
  /** Releases the owner session and stops the process that holds it; safe to call more than once. */
  fun end()
}

/**
 * The live approval owner generations bound to one database session. A generation belongs to exactly one session:
 * [closeAll] ends every admitted one while the session's database is still usable, and a generation offered after that is
 * refused, so a process can never keep an owner lease in a database the app already left.
 */
@Inject
@SingleIn(DatabaseSessionScope::class)
class SessionApprovalGenerations(private val diagnostics: Diagnostics) {
  private val lock = Any()
  private val generations = LinkedHashSet<ApprovalGeneration>()
  private var closed = false

  /** False once the session is closing; the caller must then end the generation itself. */
  fun admit(generation: ApprovalGeneration): Boolean = synchronized(lock) {
    if (closed) false else generations.add(generation)
  }

  fun remove(generation: ApprovalGeneration) {
    synchronized(lock) { generations.remove(generation) }
  }

  fun closeAll() {
    val ending = synchronized(lock) {
      closed = true
      generations.toList().also { generations.clear() }
    }
    for (generation in ending) {
      try {
        generation.end()
      } catch (_: RuntimeException) {
        diagnostics.record("approval.generation_end_failed")
      }
    }
  }
}
