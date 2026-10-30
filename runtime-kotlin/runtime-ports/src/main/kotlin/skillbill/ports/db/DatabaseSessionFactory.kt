package skillbill.ports.db

import skillbill.ports.db.model.DatabasePathProtectionResult
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.persistence.UnitOfWork
import java.nio.file.Path

interface DatabaseSessionFactory {
  /**
   * Acquires the shared lock for the selected database path. Purge and any supported operation that replaces
   * the selected database file must hold this lock for the full operation.
   */
  fun acquireDatabasePathProtection(): DatabasePathProtectionResult

  fun resolveDbPath(): Path

  fun databaseExists(): Boolean

  fun <T> read(block: (UnitOfWork) -> T): T

  fun <T> readIfPresent(block: (UnitOfWork) -> T): T? = if (databaseExists()) read(block) else null

  fun <T> transaction(block: (UnitOfWork) -> T): T

  fun <T> owningTransaction(block: (UnitOfWork) -> T): OwningTransactionResult<T>

  fun <T> transactionFenced(
    expected: DatabaseFileObservation,
    block: (UnitOfWork) -> T,
  ): FencedTransactionResult<T> =
    transaction { unit ->
      if (unit.databaseFileObservation != expected) {
        FencedTransactionResult.ChangedTarget
      } else {
        FencedTransactionResult.Committed(block(unit))
      }
    }

  fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T
}
