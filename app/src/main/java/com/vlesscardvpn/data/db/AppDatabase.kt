package com.vlesscardvpn.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.data.security.EnvelopeCipher
import com.vlesscardvpn.data.security.NodeSecrets
import com.vlesscardvpn.data.security.NodePayload
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vless_configs")
data class VlessConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val protocolType: String = "vless",
    val flow: String = "xtls-rprx-vision",
    val security: String = "reality",
    val sni: String = "yandex.ru",
    val fingerprint: String = "chrome",
    val publicKey: String = "",
    val shortId: String = "",
    val remark: String = "",
    val isActive: Boolean = false,
    val pingMs: Int = -1,
    val isFree: Boolean = false,
    val country: String = "Unknown",
    val addedAt: Long = System.currentTimeMillis(),
    val lastCheck: Long = 0L,
    val healthState: String = "UNKNOWN", // UNKNOWN, HEALTHY, DEGRADED, DEAD
    val failureCount: Int = 0,
    val source: String = "manual",
    val isFavorite: Boolean = false,
    val tcpLatencyMs: Int = -1,
    val tlsLatencyMs: Int = -1,
    val httpLatencyMs: Int = -1,
    @ColumnInfo(defaultValue = "''") val encryptedPayload: String = ""
)

internal fun VlessConfigEntity.legacyDomain(): VlessConfig = VlessConfig(
    id = id,
    name = name,
    address = address,
    port = port,
    uuid = uuid,
    protocolType = protocolType,
    flow = flow,
    security = security,
    sni = sni,
    fingerprint = fingerprint,
    publicKey = publicKey,
    shortId = shortId,
    remark = remark,
    isActive = isActive,
    pingMs = pingMs,
    isFree = isFree,
    country = country,
    addedAt = addedAt,
    lastCheck = lastCheck,
    healthState = healthState,
    failureCount = failureCount,
    source = source,
    isFavorite = isFavorite,
    tcpLatencyMs = tcpLatencyMs,
    tlsLatencyMs = tlsLatencyMs,
    httpLatencyMs = httpLatencyMs
)

/** Reads fail closed; only the explicit v1 migration is allowed to read plaintext. */
fun VlessConfigEntity.toDomain(cipher: EnvelopeCipher = NodeSecrets.cipher): VlessConfig =
    NodePayload.decode(legacyDomain(), cipher.open(id, encryptedPayload))

fun VlessConfig.toEntity(cipher: EnvelopeCipher = NodeSecrets.cipher): VlessConfigEntity = VlessConfigEntity(
    id = id, name = "", address = "", port = 0, uuid = "", protocolType = "", flow = "", security = "",
    sni = "", fingerprint = "", publicKey = "", shortId = "", remark = "", country = "", source = "",
    isActive = isActive, pingMs = pingMs, isFree = isFree, addedAt = addedAt, lastCheck = lastCheck,
    healthState = healthState, failureCount = failureCount, isFavorite = isFavorite,
    tcpLatencyMs = tcpLatencyMs, tlsLatencyMs = tlsLatencyMs, httpLatencyMs = httpLatencyMs,
    encryptedPayload = cipher.seal(id, NodePayload.encode(this))
)

@Dao
interface VlessConfigDao {
    @Query("SELECT * FROM vless_configs ORDER BY isFavorite DESC, pingMs ASC, addedAt DESC")
    fun getAllFlow(): Flow<List<VlessConfigEntity>>

    @Query("SELECT * FROM vless_configs ORDER BY isFavorite DESC, pingMs ASC, addedAt DESC")
    suspend fun getAll(): List<VlessConfigEntity>

    @Query("SELECT * FROM vless_configs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VlessConfigEntity?

    @Query("SELECT * FROM vless_configs WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveConfig(): VlessConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: VlessConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<VlessConfigEntity>)

    @Update
    suspend fun update(entity: VlessConfigEntity)

    @Query("UPDATE vless_configs SET isActive = (id = :activeId)")
    suspend fun setActive(activeId: String)

    @Query("UPDATE vless_configs SET isActive = 0")
    suspend fun clearActive()

    @Query("DELETE FROM vless_configs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM vless_configs WHERE isFree = 1")
    suspend fun clearFreeNodes()

    @Query("DELETE FROM vless_configs")
    suspend fun clearAll()
}

@Database(entities = [VlessConfigEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vlessConfigDao(): VlessConfigDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Process-wide singleton. Multiple Room instances for the same file do not
         * propagate Flow invalidation between each other, which left the UI showing
         * stale data after service/worker writes.
         */
        fun getInstance(context: android.content.Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vless_vpn.db"
                )
                    .addMigrations(NodeStorageMigration(NodeSecrets.cipher))
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .enableMultiInstanceInvalidation()
                    .build()
                    .also { instance = it }
            }
    }
}
