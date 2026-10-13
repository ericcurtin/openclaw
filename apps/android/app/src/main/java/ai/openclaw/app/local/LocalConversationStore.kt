package ai.openclaw.app.local

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val LOCAL_CHAT_DB_NAME = "local-chat.db"

internal data class LocalSessionRecord(
  val key: String,
  /** A user-chosen name; null until renamed. */
  val title: String?,
  val createdAtMs: Long,
  val updatedAtMs: Long,
)

/**
 * One plain-text turn. This is the replayable unit: role, text, time, and the client idempotency
 * key are enough to re-send the same conversation into a Gateway session later. Nothing here
 * carries attachments, tool calls, or credentials.
 */
internal data class LocalMessageRecord(
  val sessionKey: String,
  /** Position within the session, assigned by the store on append. */
  val seq: Long,
  /** `user` or `assistant`. */
  val role: String,
  val text: String,
  val timestampMs: Long,
  /** Set on user turns as `<runId>:user`, which is how the outbox proves delivery. */
  val idempotencyKey: String?,
  val runId: String?,
  /** Null for a normal turn, `aborted` or `error` for a partial assistant reply. */
  val stopReason: String?,
)

/** Durable local conversations. Unlike the Gateway transcript cache, nothing else can restore these. */
internal interface LocalConversationStore {
  suspend fun sessions(): List<LocalSessionRecord>

  suspend fun session(key: String): LocalSessionRecord?

  suspend fun upsertSession(record: LocalSessionRecord)

  suspend fun messages(sessionKey: String): List<LocalMessageRecord>

  /** Appends at the next position and returns the stored record. */
  suspend fun appendMessage(record: LocalMessageRecord): LocalMessageRecord

  suspend fun deleteSession(key: String)

  /** Removes every local conversation. */
  suspend fun clear()
}

@Entity(tableName = "local_sessions")
internal data class LocalSessionEntity(
  @PrimaryKey val sessionKey: String,
  val title: String?,
  val createdAtMs: Long,
  val updatedAtMs: Long,
)

@Entity(tableName = "local_messages", primaryKeys = ["sessionKey", "seq"])
internal data class LocalMessageEntity(
  val sessionKey: String,
  val seq: Long,
  val role: String,
  val text: String,
  val timestampMs: Long,
  val idempotencyKey: String?,
  val runId: String?,
  val stopReason: String?,
)

@Dao
internal interface LocalChatDao {
  @Query("SELECT * FROM local_sessions ORDER BY updatedAtMs DESC")
  suspend fun sessions(): List<LocalSessionEntity>

  @Query("SELECT * FROM local_sessions WHERE sessionKey = :sessionKey")
  suspend fun session(sessionKey: String): LocalSessionEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertSession(row: LocalSessionEntity)

  @Query("SELECT * FROM local_messages WHERE sessionKey = :sessionKey ORDER BY seq ASC")
  suspend fun messages(sessionKey: String): List<LocalMessageEntity>

  @Query("SELECT COALESCE(MAX(seq), 0) FROM local_messages WHERE sessionKey = :sessionKey")
  suspend fun lastSeq(sessionKey: String): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMessage(row: LocalMessageEntity)

  @Query("DELETE FROM local_messages WHERE sessionKey = :sessionKey")
  suspend fun deleteMessages(sessionKey: String)

  @Query("DELETE FROM local_sessions WHERE sessionKey = :sessionKey")
  suspend fun deleteSession(sessionKey: String)

  @Query("DELETE FROM local_messages")
  suspend fun deleteAllMessages()

  @Query("DELETE FROM local_sessions")
  suspend fun deleteAllSessions()
}

/** Separate file from the Gateway cache and outbox databases so removing local mode never touches them. */
@Database(
  entities = [LocalSessionEntity::class, LocalMessageEntity::class],
  version = 1,
  exportSchema = false,
)
internal abstract class LocalChatDatabase : RoomDatabase() {
  abstract fun dao(): LocalChatDao
}

internal class RoomLocalConversationStore(
  context: Context,
) : LocalConversationStore {
  private val appContext = context.applicationContext
  private val writeMutex = Mutex()
  private val dao: LocalChatDao by lazy {
    Room.databaseBuilder(appContext, LocalChatDatabase::class.java, LOCAL_CHAT_DB_NAME).build().dao()
  }

  override suspend fun sessions(): List<LocalSessionRecord> = dao.sessions().map { it.toRecord() }

  override suspend fun session(key: String): LocalSessionRecord? = dao.session(key)?.toRecord()

  override suspend fun upsertSession(record: LocalSessionRecord) {
    writeMutex.withLock { dao.upsertSession(record.toEntity()) }
  }

  override suspend fun messages(sessionKey: String): List<LocalMessageRecord> = dao.messages(sessionKey).map { it.toRecord() }

  override suspend fun appendMessage(record: LocalMessageRecord): LocalMessageRecord =
    writeMutex.withLock {
      val stored = record.copy(seq = dao.lastSeq(record.sessionKey) + 1)
      dao.insertMessage(stored.toEntity())
      stored
    }

  override suspend fun deleteSession(key: String) {
    writeMutex.withLock {
      dao.deleteMessages(key)
      dao.deleteSession(key)
    }
  }

  override suspend fun clear() {
    writeMutex.withLock {
      dao.deleteAllMessages()
      dao.deleteAllSessions()
    }
  }
}

private fun LocalSessionEntity.toRecord() = LocalSessionRecord(sessionKey, title, createdAtMs, updatedAtMs)

private fun LocalSessionRecord.toEntity() = LocalSessionEntity(key, title, createdAtMs, updatedAtMs)

private fun LocalMessageEntity.toRecord() = LocalMessageRecord(sessionKey, seq, role, text, timestampMs, idempotencyKey, runId, stopReason)

private fun LocalMessageRecord.toEntity() = LocalMessageEntity(sessionKey, seq, role, text, timestampMs, idempotencyKey, runId, stopReason)
