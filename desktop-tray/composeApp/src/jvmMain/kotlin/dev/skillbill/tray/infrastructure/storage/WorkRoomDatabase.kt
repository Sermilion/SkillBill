package dev.skillbill.tray.infrastructure.storage

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import skillbill.contracts.persistence.DesktopReadSchema

@Entity(tableName = "schema_migrations")
internal data class RuntimeMigrationRow(
  @PrimaryKey val name: String,
  val version: Int,
  val applied_at: String,
)

@Dao
internal interface RuntimeMigrationDao {
  @Query("SELECT * FROM schema_migrations ORDER BY version DESC LIMIT 1")
  suspend fun latest(): RuntimeMigrationRow?
}

@Database(entities = [RuntimeMigrationRow::class], version = DesktopReadSchema.VERSION, exportSchema = false)
internal abstract class WorkRoomDatabase : RoomDatabase() {
  abstract fun migrations(): RuntimeMigrationDao
  abstract fun candidates(): WorkCandidateDao
}
