package red.man10.man10itembank.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import red.man10.man10itembank.testsupport.MySqlSupport
import red.man10.man10itembank.testsupport.TestPluginContext

/**
 * テーブルが欠けた状態から起動した後、実際にプラグインが投げるSQLが通ることを確認する。
 *
 * onEnable が明示的に確認するのは Migration.run だけで、storage_log への書き込みは
 * 実行時まで失敗が分からない。以前は item_storage の有無しか見ていなかったため、
 * storage_log だけ無いDBでも起動が通り、ログ書き込みが延々と失敗していた。
 */
class MissingTableTest {

    private val opened = mutableListOf<Database>()

    private val uuid = "00000000-0000-0000-0000-000000000001"

    @AfterEach
    fun tearDown() {
        opened.forEach { it.shutdown() }
        opened.clear()
    }

    private fun connect(schema: String): Pair<Database, TestPluginContext> {
        val context = TestPluginContext(schema)
        val db = Database(context.plugin, "test").also { opened.add(it) }
        check(db.connect())
        return db to context
    }

    /** ItemData / Log が実際に投げるSQLを、移行後のスキーマに対して流す */
    private fun runRealStatements(db: Database) {

        val storage = db.transaction("在庫更新") { con ->
            con.executeUpdate(
                "INSERT INTO item_storage (player, uuid, item_id, item_key, amount, time) " +
                        "VALUES (?, ?, ?, ?, 0, now()) ON DUPLICATE KEY UPDATE id = id",
                "tester", uuid, 1, "k"
            )
            con.executeUpdate(
                "UPDATE item_storage SET amount = amount + ?, time = now() " +
                        "WHERE uuid = ? AND item_id = ? AND amount + ? BETWEEN 0 AND ?",
                10, uuid, 1, 10, Int.MAX_VALUE
            )
        }
        assertTrue(storage is DbResult.Success, "在庫操作が失敗しました: $storage")

        val index = db.query("SELECT id, item_key, price, bid, ask, tick, base64 FROM item_index WHERE disabled = 0") {
            it.next()
        }
        assertTrue(index is DbResult.Success, "ItemIndexの読み込みが失敗しました: $index")

        val storageLog = db.update(
            "INSERT INTO storage_log (item_id, item_key, order_player, order_uuid, target_player, target_uuid, " +
                    "action, edit_amount, storage_amount, world, x, y, z, time) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
            1, "k", "tester", uuid, "tester", uuid, "AddItem", 10, 10, "none", 0.0, 0.0, 0.0
        )
        assertTrue(storageLog is DbResult.Success, "storage_logへの書き込みが失敗しました: $storageLog")

        val systemLog = db.update(
            "INSERT INTO system_log (player, uuid, action, time) VALUES (?, ?, ?, now())",
            "tester", uuid, "test"
        )
        assertTrue(systemLog is DbResult.Success, "system_logへの書き込みが失敗しました: $systemLog")
    }

    @Test
    @DisplayName("空のDBから起動しても、在庫操作とログ書き込みが揃って通る")
    fun worksFromEmptyDatabase() {

        val schema = MySqlSupport.createSchema("emptydb")
        val (db, context) = connect(schema)

        assertTrue(Migration.run(db, context.logs.logger, true), context.logs.dump())

        runRealStatements(db)
    }

    @Test
    @DisplayName("storage_logだけ欠けたDBでも、起動後にログ書き込みが通る")
    fun worksWhenOnlyLogTablesWereMissing() {

        val schema = MySqlSupport.createSchema("nolog")

        //storage_log / system_log を作らない(旧実装が見逃していた状態)
        MySqlSupport.applySql(
            schema,
            MySqlSupport.currentSchemaSql().substringBefore("create table storage_log")
        )

        val (db, context) = connect(schema)

        assertTrue(Migration.run(db, context.logs.logger, true), context.logs.dump())

        runRealStatements(db)
    }

    @Test
    @DisplayName("移行前のスキーマから起動しても、在庫操作とログ書き込みが通る")
    fun worksFromLegacySchema() {

        val schema = MySqlSupport.createSchema("legacy")
        MySqlSupport.applySql(schema, MySqlSupport.legacySchemaSql())

        MySqlSupport.insertStorage(schema, uuid, 1, 100)
        MySqlSupport.insertStorage(schema, uuid, 1, 50)

        val (db, context) = connect(schema)

        assertTrue(Migration.run(db, context.logs.logger, true), context.logs.dump())

        runRealStatements(db)

        val amount = MySqlSupport.queryOne(
            schema, "SELECT amount FROM item_storage WHERE uuid = '$uuid' AND item_id = 1"
        ) { it.getInt(1) }

        //統合された150に、上の在庫操作の+10が乗る
        assertTrue(amount == 160, "在庫が想定と違います: $amount")
    }
}
