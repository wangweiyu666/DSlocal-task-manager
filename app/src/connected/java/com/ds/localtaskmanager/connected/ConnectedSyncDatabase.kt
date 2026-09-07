package com.ds.localtaskmanager.connected

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.ColumnInfo
import com.ds.localtaskmanager.data.connectedOpenHelperFactory
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cloud_session")
data class CloudSessionEntity(
    @PrimaryKey val id: String = "active",
    val accessToken: String,
    val refreshToken: String,
    val csrfToken: String,
    val accessExpiresAt: String,
    val accountId: String? = null,
    val membershipId: String? = null,
    val spaceId: String? = null,
    val spaceName: String? = null,
    val role: String? = null,
    /** Changes whenever an account is replaced, invalidating queued workers. */
    val sessionGeneration: String = "legacy",
    @ColumnInfo(defaultValue = "0") val retryUntilEpochMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val needsSync: Boolean = false,
    val blockedReason: String? = null,
)

@Entity(tableName = "cloud_entity")
data class CloudEntity(
    @PrimaryKey val key: String,
    val spaceId: String,
    val entityType: String,
    val entityId: String,
    val entityVersion: Int,
    val payloadVersion: Int,
    val payloadJson: String,
    val updatedAt: String,
)

@Entity(tableName = "cloud_outbox")
data class CloudOutboxEntity(
    @PrimaryKey val commandId: String,
    val spaceId: String,
    val semanticKey: String,
    val commandJson: String,
    val queuedAt: String,
    val attempts: Int = 0,
    val retryAtEpochMillis: Long? = null,
    val state: String = "PENDING",
    val lastError: String? = null,
)

@Entity(tableName = "cloud_sent_semantic")
data class CloudSentSemanticEntity(
    @PrimaryKey val semanticKey: String,
    val spaceId: String,
    val commandId: String,
    val acceptedAt: String,
)

@Entity(tableName = "cloud_sync_meta")
data class CloudSyncMetaEntity(
    @PrimaryKey val spaceId: String,
    val changesCursor: String?,
    val lastSyncedAt: String?,
    val timeZone: String,
    val timeZoneVersion: Int,
)

@Entity(tableName = "cloud_occurrence_map")
data class CloudOccurrenceMapEntity(
    @PrimaryKey val localKey: String,
    val spaceId: String,
    val taskId: String,
    val localOccurrenceKey: String,
    val cloudOccurrenceKey: String,
    val scheduledAt: String,
    val assignmentId: String,
    val taskRevision: Int,
    val timeZoneVersion: Int,
)

@Dao
interface ConnectedSyncDao {
    @Query("SELECT * FROM cloud_session WHERE id = 'active'") suspend fun session(): CloudSessionEntity?
    @Upsert suspend fun saveSession(value: CloudSessionEntity)
    @Query("DELETE FROM cloud_session") suspend fun clearSession()
    @Query("UPDATE cloud_session SET retryUntilEpochMillis = :until, needsSync = :needsSync, blockedReason = :blocked WHERE id = 'active' AND sessionGeneration = :generation")
    suspend fun updateSyncControl(generation: String, until: Long, needsSync: Boolean, blocked: String?)

    @Query("SELECT * FROM cloud_entity WHERE spaceId = :spaceId") suspend fun entities(spaceId: String): List<CloudEntity>
    @Query("SELECT * FROM cloud_entity WHERE spaceId = :spaceId AND entityType = :type") suspend fun entities(spaceId: String, type: String): List<CloudEntity>
    @Query("SELECT * FROM cloud_entity WHERE key = :key") suspend fun entity(key: String): CloudEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEntities(values: List<CloudEntity>)
    @Query("DELETE FROM cloud_entity WHERE key = :key") suspend fun deleteEntity(key: String)
    @Query("DELETE FROM cloud_entity WHERE spaceId = :spaceId") suspend fun clearEntities(spaceId: String)

    @Query("SELECT * FROM cloud_outbox WHERE spaceId = :spaceId ORDER BY queuedAt, rowid") suspend fun outbox(spaceId: String): List<CloudOutboxEntity>
    @Query("SELECT COUNT(*) FROM cloud_outbox WHERE spaceId = :spaceId") fun observeOutboxCount(spaceId: String): Flow<Int>
    @Query("SELECT (SELECT COUNT(*) FROM cloud_outbox WHERE semanticKey = :semanticKey) + (SELECT COUNT(*) FROM cloud_sent_semantic WHERE semanticKey = :semanticKey)") suspend fun hasSemanticKey(semanticKey: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueue(value: CloudOutboxEntity): Long
    @Upsert suspend fun markSent(value: CloudSentSemanticEntity)
    @Query("DELETE FROM cloud_sent_semantic WHERE spaceId = :spaceId") suspend fun clearSent(spaceId: String)
    @Query("DELETE FROM cloud_outbox WHERE commandId = :commandId") suspend fun deleteCommand(commandId: String)
    @Query("UPDATE cloud_outbox SET attempts = attempts + 1 WHERE commandId = :commandId") suspend fun incrementAttempts(commandId: String)
    @Query("UPDATE cloud_outbox SET attempts = attempts + 1, retryAtEpochMillis = :retryAt, state = 'RETRYABLE', lastError = :error WHERE commandId = :commandId") suspend fun markRetryable(commandId: String, retryAt: Long?, error: String?)
    @Query("UPDATE cloud_outbox SET state = 'PERMANENT_FAILURE', lastError = :error WHERE commandId = :commandId") suspend fun markPermanentFailure(commandId: String, error: String)
    @Query("DELETE FROM cloud_outbox WHERE spaceId = :spaceId") suspend fun clearOutbox(spaceId: String)

    @Query("SELECT * FROM cloud_sync_meta WHERE spaceId = :spaceId") suspend fun meta(spaceId: String): CloudSyncMetaEntity?
    @Upsert suspend fun saveMeta(value: CloudSyncMetaEntity)
    @Query("DELETE FROM cloud_sync_meta WHERE spaceId = :spaceId") suspend fun clearMeta(spaceId: String)
    @Query("SELECT * FROM cloud_occurrence_map WHERE localKey = :localKey") suspend fun occurrenceMap(localKey: String): CloudOccurrenceMapEntity?
    @Upsert suspend fun saveOccurrenceMap(value: CloudOccurrenceMapEntity)
    @Query("DELETE FROM cloud_occurrence_map WHERE spaceId = :spaceId") suspend fun clearOccurrenceMaps(spaceId: String)
}

@Database(
    entities = [CloudSessionEntity::class, CloudEntity::class, CloudOutboxEntity::class, CloudSentSemanticEntity::class, CloudSyncMetaEntity::class, CloudOccurrenceMapEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class ConnectedSyncDatabase : RoomDatabase() {
    abstract fun dao(): ConnectedSyncDao

    companion object {
        fun create(context: Context): ConnectedSyncDatabase = Room.databaseBuilder(
            context.applicationContext,
            ConnectedSyncDatabase::class.java,
            "dst-connected-sync.db",
        ).openHelperFactory(connectedOpenHelperFactory(context.applicationContext))
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}

internal val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE cloud_session ADD COLUMN sessionGeneration TEXT NOT NULL DEFAULT 'legacy'")
        database.execSQL("ALTER TABLE cloud_session ADD COLUMN retryUntilEpochMillis INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE cloud_session ADD COLUMN needsSync INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE cloud_session ADD COLUMN blockedReason TEXT")
        database.execSQL("ALTER TABLE cloud_outbox ADD COLUMN retryAtEpochMillis INTEGER")
        database.execSQL("ALTER TABLE cloud_outbox ADD COLUMN state TEXT NOT NULL DEFAULT 'PENDING'")
        database.execSQL("ALTER TABLE cloud_outbox ADD COLUMN lastError TEXT")
    }
}
