package skillbill.engine.featuretask.qa

import skillbill.engine.featuretask.qa.receipt.FeatureTaskQaOutcomeRecorder
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.persistence.UnitOfWork
import java.nio.file.Path
import java.time.Clock

private object UnusedQaOutcomeDatabase : DatabaseSessionFactory {
  override fun resolveDbPath(): Path = unused()

  override fun databaseExists(): Boolean = unused()

  override fun <T> read(block: (UnitOfWork) -> T): T = unused()

  override fun <T> transaction(block: (UnitOfWork) -> T): T = unused()

  override fun <T> owningTransaction(block: (UnitOfWork) -> T): OwningTransactionResult<T> = unused()

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = unused()

  private fun unused(): Nothing =
    throw UnsupportedOperationException("This test did not supply a database for full-run QA outcomes.")
}

fun unusedQaOutcomeRecorder(): FeatureTaskQaOutcomeRecorder =
  FeatureTaskQaOutcomeRecorder(UnusedQaOutcomeDatabase, Clock.systemUTC())
