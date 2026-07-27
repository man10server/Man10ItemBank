package red.man10.mibtest

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * 検証結果をDBそのもので照合するための最小限のJDBCラッパ。
 * プラグイン本体の Database は使わない(本体の実装を経由せず素のDBを見るため)。
 */
class Db(private val url: String, private val user: String, private val password: String) {

    fun connection(): Connection = DriverManager.getConnection(url, user, password)

    fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T =
        connection().use { con -> query(con, sql, *params, mapper = mapper) }

    fun <T> query(con: Connection, sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T =
        con.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeQuery().use { rs -> mapper(rs) }
        }

    fun update(sql: String, vararg params: Any?): Int =
        connection().use { con -> update(con, sql, *params) }

    fun update(con: Connection, sql: String, vararg params: Any?): Int =
        con.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeUpdate()
        }

    fun int(sql: String, vararg params: Any?): Int =
        query(sql, *params) { rs -> if (rs.next()) rs.getInt(1) else 0 }

    fun longOrNull(sql: String, vararg params: Any?): Long? =
        query(sql, *params) { rs -> if (rs.next()) rs.getLong(1).takeUnless { rs.wasNull() } else null }

    ////////////////////////////////
    //      在庫まわりの読み取り
    ////////////////////////////////

    fun stock(uuid: String, itemId: Int): Int =
        int("SELECT COALESCE(amount, 0) FROM item_storage WHERE uuid = ? AND item_id = ?", uuid, itemId)

    fun storageRowCount(uuid: String, itemId: Int): Int =
        int("SELECT COUNT(*) FROM item_storage WHERE uuid = ? AND item_id = ?", uuid, itemId)

    /** storage_log の変動量合計。符号付きで記録されていれば在庫と一致する */
    fun loggedDelta(uuid: String, itemId: Int): Long =
        longOrNull(
            "SELECT SUM(edit_amount) FROM storage_log WHERE target_uuid = ? AND item_id = ?",
            uuid, itemId
        ) ?: 0L

    fun lastLog(uuid: String, itemId: Int): Triple<String, Int, Int?>? =
        query(
            "SELECT action, edit_amount, storage_amount FROM storage_log " +
                    "WHERE target_uuid = ? AND item_id = ? ORDER BY id DESC LIMIT 1",
            uuid, itemId
        ) { rs ->
            if (!rs.next()) null
            else Triple(
                rs.getString("action") ?: "",
                rs.getInt("edit_amount"),
                rs.getInt("storage_amount").takeUnless { rs.wasNull() }
            )
        }
}
