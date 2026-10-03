package com.vlesscardvpn

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vlesscardvpn.data.db.*
import com.vlesscardvpn.data.security.EnvelopeCipher
import com.vlesscardvpn.domain.VlessConfig
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import javax.crypto.spec.SecretKeySpec

/** Deterministic late-result interleavings on the actual SQLite/Room implementation. */
@RunWith(AndroidJUnit4::class)
class NodeMetadataUpdateTest {
    private lateinit var db: AppDatabase
    private val cipher = EnvelopeCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    }
    @After fun cleanup() { db.close() }
    private suspend fun add(id: String, active: Boolean = false) = db.vlessConfigDao().insertOrUpdate(
        VlessConfig(id = id, name = id, address = "vpn.example.org", port = 443, uuid = "fixture", isActive = active).toEntity(cipher))

    @Test fun latePortAndHealthResultsPreserveNewSelectionFavoriteAndEncryptedPayload() = runBlocking {
        val dao = db.vlessConfigDao()
        add("a", true); add("b")
        val before = dao.getById("a")!!.encryptedPayload
        // A probe began for A; user then picked B and starred A before it completed.
        dao.setActive("b"); dao.toggleFavorite("a")
        dao.recordPortCheck("a", 123, 123, -1, 10)
        dao.recordAutoTcpHint("a", 110)
        dao.recordTunnelHealth("a", false, -1, 11)
        val a = dao.getById("a")!!
        assertFalse(a.isActive); assertTrue(a.isFavorite)
        assertEquals(before, a.encryptedPayload)
        assertEquals("b", dao.getActiveConfig()!!.id)
        assertEquals(1, dao.getAll().count { it.isActive })
    }
    @Test fun lateResultsDoNotResurrectDeletedNode() = runBlocking {
        val dao = db.vlessConfigDao(); add("deleted"); dao.deleteById("deleted")
        dao.recordAutoTcpHint("deleted", 100)
        dao.recordPortCheck("deleted", 100, 100, -1, 10)
        dao.recordTunnelHealth("deleted", true, 200, 11)
        dao.toggleFavorite("deleted")
        assertTrue(dao.getAll().isEmpty())
    }
    @Test fun healthFailuresIncrementCurrentCounterAndSuccessResetsIt() = runBlocking {
        val dao = db.vlessConfigDao(); add("node")
        dao.recordTunnelHealth("node", false, -1, 10)
        dao.recordTunnelHealth("node", false, -1, 11)
        assertEquals(2, dao.getById("node")!!.failureCount)
        dao.recordTunnelHealth("node", true, 120, 12)
        val row = dao.getById("node")!!
        assertEquals(0, row.failureCount); assertEquals("HEALTHY", row.healthState)
        assertEquals(120, row.httpLatencyMs)
        dao.toggleFavorite("node"); dao.toggleFavorite("node")
        assertFalse(dao.getById("node")!!.isFavorite)
    }
}
