package com.vlesscardvpn

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vlesscardvpn.data.db.*
import com.vlesscardvpn.data.security.*
import kotlinx.coroutines.runBlocking
import javax.crypto.spec.SecretKeySpec
import java.security.KeyStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual Android migration, rollback, secure-delete and Keystore regressions. */
@RunWith(AndroidJUnit4::class)
class NodeStorageMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val fileName = "node-migration-test.db"
    private val cipher = EnvelopeCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
    @Before fun clear() { context.deleteDatabase(fileName) }
    @After fun cleanup() { context.deleteDatabase(fileName) }
    private fun legacy() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(fileName), null).use { db ->
            db.execSQL(LEGACY_SQL)
            val values = ContentValues().apply {
                put("id", "legacy-1"); put("name", "Legacy"); put("address", "vpn.example.org"); put("port", 443)
                put("uuid", "00000000-0000-4000-8000-000000000001"); put("protocolType", "vless")
                put("flow", ""); put("security", "tls"); put("sni", "vpn.example.org"); put("fingerprint", "chrome")
                put("publicKey", "key-fixture"); put("shortId", "aabb"); put("remark", "private")
                put("isActive", 1); put("pingMs", 123); put("isFree", 0); put("country", "Unknown")
                put("addedAt", 12L); put("lastCheck", 34L); put("healthState", "UNKNOWN"); put("failureCount", 2)
                put("source", "manual"); put("isFavorite", 1); put("tcpLatencyMs", 123); put("tlsLatencyMs", -1); put("httpLatencyMs", -1)
            }
            db.insertOrThrow("vless_configs", null, values)
            db.version = 1
        }
    }
    @Test fun migrationEncryptsExistingRowWithoutLosingIdentityOrFavorite() = runBlocking {
        legacy()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, fileName)
            .addMigrations(NodeStorageMigration(cipher)).setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            val row = room.vlessConfigDao().getAll().single()
            room.openHelper.writableDatabase.query("PRAGMA secure_delete").use { result ->
                assertTrue(result.moveToFirst())
                assertEquals(1, result.getInt(0))
            }
            assertEquals("", row.uuid); assertEquals("", row.address); assertEquals("", row.source)
            assertTrue(row.encryptedPayload.startsWith("v1:"))
            val node = row.toDomain(cipher)
            assertEquals("legacy-1", node.id); assertEquals("vpn.example.org", node.address)
            assertTrue(node.isFavorite); assertTrue(node.isActive); assertEquals(123, node.pingMs)
            assertEquals("tcp", node.transport)
        } finally { room.close() }
    }
    @Test fun encryptionFailureRollsBackAndNeverDeletesLegacyRows() {
        legacy()
        val failed = EnvelopeCipher { throw IllegalStateException("fixture key failure") }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, fileName).addMigrations(NodeStorageMigration(failed)).build()
        try {
            try { room.openHelper.writableDatabase; Assert.fail("Expected migration failure") } catch (_: IllegalStateException) { }
        } finally { room.close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(fileName).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1, db.version)
            db.rawQuery("SELECT uuid FROM vless_configs", null).use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals("00000000-0000-4000-8000-000000000001", rows.getString(0))
            }
        }
    }
    @Test fun androidKeystoreRoundTripUsesNonExportableKey() {
        val alias = "vless-test-only-" + java.util.UUID.randomUUID().toString()
        try {
            val provider = AndroidNodeKey(alias)
            val localCipher = EnvelopeCipher(provider::get)
            val raw = localCipher.seal("device-test", "fixture-secret")
            assertEquals("fixture-secret", localCipher.open("device-test", raw))
            assertNull(provider.get(false).encoded)
        } finally { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    }
    companion object { private const val LEGACY_SQL = """CREATE TABLE IF NOT EXISTS `vless_configs` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `address` TEXT NOT NULL, `port` INTEGER NOT NULL, `uuid` TEXT NOT NULL, `protocolType` TEXT NOT NULL, `flow` TEXT NOT NULL, `security` TEXT NOT NULL, `sni` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `publicKey` TEXT NOT NULL, `shortId` TEXT NOT NULL, `remark` TEXT NOT NULL, `isActive` INTEGER NOT NULL, `pingMs` INTEGER NOT NULL, `isFree` INTEGER NOT NULL, `country` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `lastCheck` INTEGER NOT NULL, `healthState` TEXT NOT NULL, `failureCount` INTEGER NOT NULL, `source` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, `tcpLatencyMs` INTEGER NOT NULL, `tlsLatencyMs` INTEGER NOT NULL, `httpLatencyMs` INTEGER NOT NULL, PRIMARY KEY(`id`))""" }
}
