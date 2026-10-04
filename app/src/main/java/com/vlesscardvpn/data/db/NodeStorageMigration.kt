package com.vlesscardvpn.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vlesscardvpn.data.security.EnvelopeCipher
import com.vlesscardvpn.data.security.NodePayload
import com.vlesscardvpn.domain.VlessConfig

/** Room runs the migration transactionally: failures must roll back, never delete user records. */
class NodeStorageMigration(private val cipher: EnvelopeCipher) : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // This PRAGMA returns a row on Android SQLite. execSQL rejects row-returning
        // statements and aborts the migration before any server can be loaded.
        db.query("PRAGMA secure_delete=ON").use { result ->
            check(result.moveToFirst() && result.getInt(0) == 1) {
                "SQLite secure_delete could not be enabled"
            }
        }
        db.execSQL("ALTER TABLE vless_configs ADD COLUMN encryptedPayload TEXT NOT NULL DEFAULT ''")
        db.query("SELECT * FROM vless_configs").use { rows ->
            while (rows.moveToNext()) {
                fun str(name: String) = rows.getString(rows.getColumnIndexOrThrow(name))
                val id = str("id")
                val old = VlessConfig(id = id, name = str("name"), address = str("address"),
                    port = rows.getInt(rows.getColumnIndexOrThrow("port")), uuid = str("uuid"),
                    protocolType = str("protocolType"), flow = str("flow"), security = str("security"),
                    sni = str("sni"), fingerprint = str("fingerprint"), publicKey = str("publicKey"),
                    shortId = str("shortId"), remark = str("remark"), country = str("country"), source = str("source"))
                // v1 did not persist transport. Missing old paths cannot be reconstructed; reimport those nodes.
                val payload = cipher.seal(id, NodePayload.encode(old))
                db.execSQL("""UPDATE vless_configs SET encryptedPayload=?, name='', address='', port=0, uuid='',
                    protocolType='', flow='', security='', sni='', fingerprint='', publicKey='', shortId='',
                    remark='', country='', source='' WHERE id=?""", arrayOf(payload, id))
            }
        }
        // No claim of forensic erasure: SQLite journals, OS snapshots and old backups need separate handling.
    }
}
