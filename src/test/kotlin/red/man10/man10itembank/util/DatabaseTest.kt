package red.man10.man10itembank.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import red.man10.man10itembank.testsupport.MySqlProxy
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

    ////////////////////////////////
    //      タイムアウトの取り決め
    ////////////////////////////////

    @Test
    @DisplayName("行ロックの待ち時間はsocketTimeoutより短く設定される")
    fun lockWaitIsShorterThanSocketTimeout() {

        //socketTimeoutが先に切れると「サーバー側で文が実行中か分からない」状態になり、
        //ロールバックが保証される1205と違って再試行も成否の判断もできなくなる
        val (db, _) = database { set("mysql.pool.socketTimeoutMs", 3_000) }
        db.connect()

        val lockWait = db.query("SELECT @@SESSION.innodb_lock_wait_timeout") { rs ->
            if (rs.next()) rs.getInt(1) else -1
        }

        assertEquals(2, (lockWait as DbResult.Success).value)
    }

    @Test
    @DisplayName("socketTimeoutを変えるとロック待ちも追随する")
    fun lockWaitFollowsSocketTimeout() {

        val (db, _) = database { set("mysql.pool.socketTimeoutMs", 8_000) }
        db.connect()

        val lockWait = db.query("SELECT @@SESSION.innodb_lock_wait_timeout") { rs ->
            if (rs.next()) rs.getInt(1) else -1
        }

        assertEquals(7, (lockWait as DbResult.Success).value)
    }

    @Test
    @DisplayName("起動用コネクションは長い応答待ちを使う")
    fun startupConnectionUsesLongTimeout() {

        //移行のALTERは通常のsocketTimeoutを超えうるため、専用コネクションが別設定を持つ
        val (db, _) = database {
            set("mysql.pool.socketTimeoutMs", 3_000)
            set("mysql.pool.startupSocketTimeoutMs", 600_000)
        }
        db.connect()

        //通常のプールでは3秒で切れる待ち時間を、起動用コネクションでは越えられる
        val result = db.withStartupConnection("sleep") { con ->
            con.executeQuery("SELECT SLEEP(4)") { rs -> rs.next() }
        }

        assertTrue(result is DbResult.Success, "起動用コネクションが4秒の応答を待てませんでした: $result")

        val timedOut = db.query("SELECT SLEEP(4)") { rs -> rs.next() }

        assertTrue(timedOut is DbResult.Failure, "通常のプールが3秒を超えて待ってしまいました")
    }

    ////////////////////////////////
    //      サーキットブレーカー
    ////////////////////////////////

    /** MySQLの手前にプロキシを挟み、起動後にDBが落ちる状況を作る */
    private fun throughProxy(
        proxy: MySqlProxy,
        configure: org.bukkit.configuration.file.YamlConfiguration.() -> Unit = {}
    ): Pair<Database, TestPluginContext> {

        val context = TestPluginContext(schema) {
            set("mysql.port", proxy.port)
            set("mysql.pool.connectionTimeoutMs", 400)
            set("mysql.pool.connectTimeoutMs", 400)
            set("mysql.circuitBreaker.failureThreshold", 3)
            set("mysql.circuitBreaker.probeIntervalMs", 500)
            configure()
        }

        val db = Database(context.plugin, "breaker").also { opened.add(it) }

        return db to context
    }

    @Test
    @DisplayName("接続失敗が続くと、以降は接続を試みずに即座に失敗する")
    fun opensCircuitAfterRepeatedFailures() {

        MySqlProxy().use { proxy ->

            val (db, context) = throughProxy(proxy)

            assertTrue(db.connect(), "プロキシ経由で接続できませんでした")
            assertTrue(db.update("UPDATE counter SET value = 1 WHERE id = 1") is DbResult.Success)

            //DBが落ちた状態にする
            proxy.forwarding = false
            proxy.cutLiveConnections()

            repeat(3) { db.update("UPDATE counter SET value = 1 WHERE id = 1") }

            assertTrue(db.isCircuitOpen, "3回失敗してもブレーカーが開いていません\n${context.logs.dump()}")
            assertTrue(context.logs.anyContains("即座に失敗させます"), context.logs.dump())

            //開いた後は接続そのものを試みない。時間ではなく接続回数で確かめる
            val before = proxy.accepted

            val elapsed = kotlin.system.measureTimeMillis {
                repeat(50) {
                    assertTrue(db.update("UPDATE counter SET value = 1 WHERE id = 1") is DbResult.Failure)
                }
            }

            assertEquals(before, proxy.accepted, "ブレーカーが開いているのに接続を試みています")
            assertTrue(elapsed < 200, "即時失敗になっていません(50件で${elapsed}ms)")
        }
    }

    @Test
    @DisplayName("即時失敗中でも、一定間隔で1件だけ通して復帰を確かめる")
    fun probesWhileOpen() {

        MySqlProxy().use { proxy ->

            val (db, _) = throughProxy(proxy)

            assertTrue(db.connect())

            proxy.forwarding = false
            proxy.cutLiveConnections()

            repeat(3) { db.update("UPDATE counter SET value = 1 WHERE id = 1") }
            assertTrue(db.isCircuitOpen)

            //間隔前は接続を試みない
            val before = proxy.accepted
            repeat(10) { db.update("UPDATE counter SET value = 1 WHERE id = 1") }
            assertEquals(before, proxy.accepted, "間隔前に接続を試みています")

            Thread.sleep(600)

            //間隔が過ぎたら1件だけ試みる
            db.update("UPDATE counter SET value = 1 WHERE id = 1")
            assertTrue(proxy.accepted > before, "復帰確認の試行が行われていません")
        }
    }

    @Test
    @DisplayName("即時失敗中の接続試行は、操作数ではなく経過時間で決まる")
    fun probeRateIsBoundedByInterval() {

        // 復帰確認の間隔を「許可を出した時点」から測ると、試行自体が
        // 接続タイムアウト分かかるせいで終わった瞬間に期限切れになり、
        // 結局すべての操作が接続を試みてキューが詰まる。
        // それを検出するため、接続タイムアウトと間隔を同じ長さにする
        MySqlProxy().use { proxy ->

            val (db, _) = throughProxy(proxy) {
                set("mysql.pool.connectionTimeoutMs", 500)
                set("mysql.pool.connectTimeoutMs", 500)
                set("mysql.circuitBreaker.probeIntervalMs", 500)
            }

            assertTrue(db.connect())

            proxy.forwarding = false
            proxy.cutLiveConnections()

            repeat(3) { db.update("UPDATE counter SET value = 1 WHERE id = 1") }
            assertTrue(db.isCircuitOpen)

            val before = proxy.accepted
            val started = System.currentTimeMillis()
            var operations = 0

            while (System.currentTimeMillis() - started < 2_000) {
                db.update("UPDATE counter SET value = 1 WHERE id = 1")
                operations++
            }

            val attempts = proxy.accepted - before

            //即時失敗なら2秒で数千件は捌ける
            assertTrue(operations > 100, "即時失敗になっていません(2秒で${operations}件)")
            //接続試行は 2秒 / 500ms = 4回程度に収まるはず
            assertTrue(attempts <= 8, "接続試行が多すぎます(${attempts}回 / ${operations}件)")
        }
    }

    @Test
    @DisplayName("DBが戻ればブレーカーは閉じ、通常の処理に戻る")
    fun closesCircuitAfterRecovery() {

        MySqlProxy().use { proxy ->

            val (db, context) = throughProxy(proxy)

            assertTrue(db.connect())

            proxy.forwarding = false
            proxy.cutLiveConnections()

            repeat(3) { db.update("UPDATE counter SET value = 1 WHERE id = 1") }
            assertTrue(db.isCircuitOpen)

            //DBが戻る
            proxy.forwarding = true
            Thread.sleep(600)

            //復帰確認の1件が通る
            var recovered = false
            repeat(10) {
                if (db.update("UPDATE counter SET value = 42 WHERE id = 1") is DbResult.Success) {
                    recovered = true
                    return@repeat
                }
                Thread.sleep(600)
            }

            assertTrue(recovered, "復帰できませんでした\n${context.logs.dump()}")
            assertFalse(db.isCircuitOpen, "復帰後もブレーカーが開いています")
            assertTrue(context.logs.anyContains("接続が回復しました"), context.logs.dump())
            assertEquals(42, currentValue())
        }
    }

    @Test
    @DisplayName("SQL自体の失敗ではブレーカーを開かない")
    fun sqlErrorDoesNotOpenCircuit() {

        val (db, _) = database()
        db.connect()

        //接続はできているので、在庫処理を止める理由にならない
        repeat(5) { db.update("UPDATE counter SET no_such_column = 1 WHERE id = 1") }

        assertFalse(db.isCircuitOpen, "SQLエラーでブレーカーが開いています")
        assertTrue(db.update("UPDATE counter SET value = 7 WHERE id = 1") is DbResult.Success)
    }

    private fun currentValue(): Int =
        MySqlSupport.queryOne(schema, "SELECT value FROM counter WHERE id = 1") { it.getInt(1) }
}
