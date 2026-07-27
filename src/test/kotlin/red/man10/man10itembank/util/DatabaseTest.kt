package red.man10.man10itembank.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import red.man10.man10itembank.testsupport.MySqlSupport
import red.man10.man10itembank.testsupport.TestPluginContext

/**
 * 接続層の検証。実MySQLに対して実行する。
 */
class DatabaseTest {

    private lateinit var schema: String
    private val opened = mutableListOf<Database>()

    @BeforeEach
    fun setUp() {
        schema = MySqlSupport.createSchema("dbtest")
        MySqlSupport.execute(
            schema,
            "CREATE TABLE counter (id INT PRIMARY KEY, value INT NOT NULL)",
            "INSERT INTO counter (id, value) VALUES (1, 100)"
        )
    }

    @AfterEach
    fun tearDown() {
        opened.forEach { it.shutdown() }
        opened.clear()
    }

    private fun database(
        user: String = MySqlSupport.APP_USER,
        password: String = MySqlSupport.APP_PASSWORD,
        configure: org.bukkit.configuration.file.YamlConfiguration.() -> Unit = {}
    ): Pair<Database, TestPluginContext> {

        val context = TestPluginContext(schema, user, password, configure)
        val db = Database(context.plugin, "test").also { opened.add(it) }

        return db to context
    }

    ////////////////////////////////
    //      接続
    ////////////////////////////////

    @Test
    @DisplayName("正しい設定で接続でき、shutdown後は利用不可になる")
    fun connects() {

        val (db, _) = database()

        assertTrue(db.connect(), "接続できませんでした")
        assertTrue(db.isAvailable)

        db.shutdown()

        assertFalse(db.isAvailable)
    }

    @Test
    @DisplayName("必須設定が空なら接続せず、不足している項目名をログに出す")
    fun refusesEmptyConfig() {

        val (db, context) = database { set("mysql.host", "") }

        assertFalse(db.connect())
        assertTrue(context.logs.anyContains("mysql.host"), context.logs.dump())
    }

    @Test
    @DisplayName("認証に失敗したら接続しない")
    fun refusesBadCredentials() {

        val (db, context) = database(password = "wrong-password")

        assertFalse(db.connect())
        assertTrue(context.logs.severeMessages().isNotEmpty(), context.logs.dump())
    }

    ////////////////////////////////
    //      実行
    ////////////////////////////////

    @Test
    @DisplayName("queryはマッパーの戻り値をSuccessで返す")
    fun queryReturnsValue() {

        val (db, _) = database()
        db.connect()

        val result = db.query("SELECT value FROM counter WHERE id = ?", 1) { rs ->
            if (rs.next()) rs.getInt(1) else null
        }

        assertEquals(100, (result as DbResult.Success).value)
    }

    @Test
    @DisplayName("updateは影響行数を返し、0行も成功として返す")
    fun updateReturnsAffectedRows() {

        val (db, _) = database()
        db.connect()

        val hit = db.update("UPDATE counter SET value = value + 1 WHERE id = ?", 1)
        assertEquals(1, (hit as DbResult.Success).value)

        //一致する行が無い場合。失敗ではなく0行として返る(呼び出し側が行数を見る前提)
        val miss = db.update("UPDATE counter SET value = value + 1 WHERE id = ?", 999)
        assertEquals(0, (miss as DbResult.Success).value)

        assertEquals(101, currentValue())
    }

    @Test
    @DisplayName("useAffectedRows=false のため、同値更新でも1行として返る")
    fun sameValueUpdateCountsAsMatched() {

        val (db, _) = database()
        db.connect()

        val result = db.update("UPDATE counter SET value = ? WHERE id = ?", 100, 1)

        //ここが0を返す設定だと、成功をInsufficientと誤判定して在庫操作が壊れる
        assertEquals(1, (result as DbResult.Success).value)
    }

    @Test
    @DisplayName("transactionは例外でロールバックする")
    fun transactionRollsBack() {

        val (db, _) = database()
        db.connect()

        val result = db.transaction("rollback test") { con ->
            con.executeUpdate("UPDATE counter SET value = 999 WHERE id = 1")
            error("意図的な失敗")
        }

        assertTrue(result is DbResult.Failure)
        assertEquals(100, currentValue(), "ロールバックされていません")
    }

    @Test
    @DisplayName("transactionは正常終了でコミットする")
    fun transactionCommits() {

        val (db, _) = database()
        db.connect()

        val result = db.transaction("commit test") { con ->
            con.executeUpdate("UPDATE counter SET value = 555 WHERE id = 1")
        }

        assertEquals(1, (result as DbResult.Success).value)
        assertEquals(555, currentValue())
    }

    @Test
    @DisplayName("コネクションは必ずプールへ返却される")
    fun releasesConnections() {

        //旧実装は接続を閉じておらず、倉庫の初回アクセス毎にリークしていた。
        //プールサイズ1で繰り返し実行できることをもって返却を確認する
        val (db, _) = database { set("mysql.pool.maximumPoolSize", 1) }
        db.connect()

        repeat(50) { i ->
            val result = db.update("UPDATE counter SET value = ? WHERE id = 1", i)
            assertTrue(result is DbResult.Success, "${i}回目で借りられなくなりました")
        }

        //トランザクション経路も同様に返却されること
        repeat(50) { i ->
            val result = db.transaction("loop $i") { con ->
                con.executeUpdate("UPDATE counter SET value = ? WHERE id = 1", i)
            }
            assertTrue(result is DbResult.Success, "${i}回目のトランザクションで借りられなくなりました")
        }
    }

    ////////////////////////////////
    //      失敗と再試行
    ////////////////////////////////

    @Test
    @DisplayName("プール停止後の実行はFailureを返す")
    fun failsAfterShutdown() {

        val (db, context) = database()
        db.connect()
        db.shutdown()

        val result = db.query("SELECT 1") { it.next() }

        assertTrue(result is DbResult.Failure)
        assertTrue(context.logs.anyContains("接続プールが利用できません"), context.logs.dump())
    }

    @Test
    @DisplayName("再試行対象外のエラーは即座にFailureになる")
    fun doesNotRetryPermanentErrors() {

        val (db, context) = database()
        db.connect()

        val result = db.update("UPDATE counter SET no_such_column = 1 WHERE id = 1")

        assertTrue(result is DbResult.Failure)
        assertFalse(context.logs.anyContains("再試行"), "再試行してはいけないエラーで再試行しています")
    }

    @Test
    @DisplayName("ロック待ちタイムアウト(1205)は設定回数だけ再試行してから失敗する")
    fun retriesLockWaitTimeout() {

        val (db, context) = database { set("mysql.maxRetry", 2) }
        db.connect()

        //別コネクションで対象行の排他ロックを保持し続ける
        MySqlSupport.appConnection(schema).use { blocker ->

            blocker.autoCommit = false
            blocker.createStatement().use { it.executeQuery("SELECT * FROM counter WHERE id = 1 FOR UPDATE").close() }

            val result = db.update("UPDATE counter SET value = value + 1 WHERE id = 1")

            assertTrue(result is DbResult.Failure, "ロックを取れないのに成功しています")

            val retries = context.logs.messages().count { it.contains("一時的な競合(errorCode=1205)") }
            assertEquals(2, retries, "再試行回数が想定と違います\n${context.logs.dump()}")

            blocker.rollback()
        }

        //ロックを解放すれば通る
        assertTrue(db.update("UPDATE counter SET value = value + 1 WHERE id = 1") is DbResult.Success)
    }

    private fun currentValue(): Int =
        MySqlSupport.queryOne(schema, "SELECT value FROM counter WHERE id = 1") { it.getInt(1) }
}
