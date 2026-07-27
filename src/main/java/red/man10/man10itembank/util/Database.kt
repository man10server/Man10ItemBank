package red.man10.man10itembank.util

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.plugin.java.JavaPlugin
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * DBアクセスの結果。
 *
 * 「失敗したこと」を戻り値の型に持たせ、呼び出し側が必ず判断するようにしている。
 * 以前の実装は失敗しても true / -1 / 0 を返していたため、
 * 書き込み失敗にもかかわらず処理が継続してアイテムの消失・複製が起きていた。
 */
sealed interface DbResult<out T> {

    data class Success<out T>(val value: T) : DbResult<T>

    data class Failure(val message: String, val cause: Throwable? = null) : DbResult<Nothing>
}

fun <T> DbResult<T>.valueOrNull(): T? = (this as? DbResult.Success)?.value

/**
 * パラメータ付きSQL。キューに積んで後で実行するために使う。
 */
data class SqlStatement(val sql: String, val params: List<Any?>)

internal fun PreparedStatement.bindParams(params: Array<out Any?>) {
    params.forEachIndexed { i, param ->
        val index = i + 1
        when (param) {
            null -> setObject(index, null)
            // Connector/JはUUIDを直接扱えないため文字列にする
            is UUID -> setString(index, param.toString())
            else -> setObject(index, param)
        }
    }
}

/**
 * Database.transaction のブロック内で使う。UPDATE/INSERT/DELETEの影響行数を返す。
 */
fun Connection.executeUpdate(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { ps ->
        ps.bindParams(params)
        ps.executeUpdate()
    }

/**
 * Database.transaction のブロック内で使う。ResultSetはこの中で消費すること。
 */
fun <T> Connection.executeQuery(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T =
    prepareStatement(sql).use { ps ->
        ps.bindParams(params)
        ps.executeQuery().use { rs -> mapper(rs) }
    }

/**
 * HikariCPによるコネクションプール。
 *
 * - コネクションは必ずプールから借りて use{} で返却する(旧実装はクエリ毎に新規接続し、閉じ忘れていた)
 * - SQLは全てPreparedStatement(旧実装は文字列連結＋自作エスケープ)
 * - ResultSetはコネクション有効期間内で必ずマッパーが消費する(閉じたあと参照する事故を型で防ぐ)
 */
class Database(private val plugin: JavaPlugin, private val poolName: String) {

    private companion object {
        const val DRIVER_CLASS = "com.mysql.cj.jdbc.Driver"
        const val DEFAULT_PORT = 3306

        /**
         * 再試行してよいエラーコード。
         * MySQLがステートメントをロールバックすることを保証するものだけを対象にする。
         * (接続断などは適用済みか判別できないため再試行しない ＝ 二重適用を防ぐ)
         */
        val RETRYABLE_ERROR_CODES = setOf(
            1205, // ER_LOCK_WAIT_TIMEOUT
            1213, // ER_LOCK_DEADLOCK
        )
    }

    private var dataSource: HikariDataSource? = null
    private var maxRetry = 2

    //起動時の移行など、socketTimeoutを超えうる処理のために保持しておく
    private var startupUrl = ""
    private var jdbcUser = ""
    private var jdbcPassword = ""

    /**
     * 接続失敗のスタックトレースは最初の1回だけ出す。
     * 在庫処理は単一スレッドで動くため、DBが落ちている間は同じ例外が
     * キューの件数だけ積まれてログが埋まる。
     */
    private val stackTraceLogged = AtomicBoolean(false)

    val isAvailable: Boolean
        get() = dataSource?.isClosed == false

    ////////////////////////////////
    //      接続
    ////////////////////////////////

    /**
     * config.ymlを読んでプールを初期化する。メインスレッドから呼ぶこと。
     * 設定不備・接続不能はここで false を返し、プラグイン側で無効化する。
     */
    fun connect(): Boolean {

        val config = plugin.config

        val host = config.getString("mysql.host")?.trim().orEmpty()
        val db = config.getString("mysql.db")?.trim().orEmpty()
        val user = config.getString("mysql.user")?.trim().orEmpty()
        val pass = config.getString("mysql.pass") ?: ""
        val port = readPort(config.get("mysql.port"))

        val missing = mutableListOf<String>()
        if (host.isEmpty()) missing.add("mysql.host")
        if (db.isEmpty()) missing.add("mysql.db")
        if (user.isEmpty()) missing.add("mysql.user")

        if (missing.isNotEmpty()) {
            plugin.logger.severe("config.yml の ${missing.joinToString(" / ")} が未設定です。")
            return false
        }

        maxRetry = config.getInt("mysql.maxRetry", 2).coerceIn(0, 5)

        val useSsl = config.getBoolean("mysql.useSsl", false)
        val allowPublicKeyRetrieval = config.getBoolean("mysql.allowPublicKeyRetrieval", true)

        val socketTimeoutMs = config.getLong("mysql.pool.socketTimeoutMs", 3_000L)
        //プールからコネクションを借りる待ちの上限
        val borrowTimeoutMs = config.getLong("mysql.pool.connectionTimeoutMs", 1_000L)
        //TCP接続確立の上限。以前はborrowTimeoutと同じ値を共用していた
        val connectTimeoutMs = config.getLong("mysql.pool.connectTimeoutMs", 1_000L)
        val startupSocketTimeoutMs = config.getLong("mysql.pool.startupSocketTimeoutMs", 600_000L)

        jdbcUser = user
        jdbcPassword = pass

        val url = buildUrl(host, port, db, useSsl, allowPublicKeyRetrieval, connectTimeoutMs, socketTimeoutMs)
        startupUrl = buildUrl(host, port, db, useSsl, allowPublicKeyRetrieval, connectTimeoutMs, startupSocketTimeoutMs)

        val hikari = HikariConfig().apply {
            this.poolName = "${this@Database.poolName}-pool"
            this.driverClassName = DRIVER_CLASS
            this.jdbcUrl = url
            this.username = user
            this.password = pass

            maximumPoolSize = config.getInt("mysql.pool.maximumPoolSize", 6).coerceAtLeast(1)
            minimumIdle = config.getInt("mysql.pool.minimumIdle", 1).coerceIn(0, maximumPoolSize)
            //HikariCPの下限は250ms
            connectionTimeout = borrowTimeoutMs.coerceAtLeast(250L)
            idleTimeout = config.getLong("mysql.pool.idleTimeoutMs", 300_000L)
            maxLifetime = config.getLong("mysql.pool.maxLifetimeMs", 1_200_000L)
            //HikariCPは validationTimeout < connectionTimeout を要求する
            validationTimeout = config.getLong("mysql.pool.validationTimeoutMs", 1_000L)
                .coerceIn(250L, (connectionTimeout - 100L).coerceAtLeast(250L))
            // 借りっぱなしのコネクションを検出してスタックトレースを出す
            leakDetectionThreshold = config.getLong("mysql.pool.leakDetectionThresholdMs", 30_000L)

            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            addDataSourceProperty("useServerPrepStmts", "true")
        }

        return try {
            val ds = HikariDataSource(hikari)
            ds.connection.use { it.isValid(3) }
            dataSource = ds
            plugin.logger.info(
                "[$poolName] MySQL接続プールを初期化しました " +
                        "($host:$port/$db ssl=$useSsl maxPoolSize=${hikari.maximumPoolSize})"
            )
            true
        } catch (e: Throwable) {
            plugin.logger.log(Level.SEVERE, "[$poolName] MySQLへの接続に失敗しました ($host:$port/$db)", e)
            false
        }
    }

    fun shutdown() {
        dataSource?.let {
            runCatching { it.close() }
                .onFailure { e -> plugin.logger.log(Level.WARNING, "[$poolName] プールの停止に失敗", e) }
        }
        dataSource = null
    }

    private fun buildUrl(
        host: String,
        port: Int,
        db: String,
        useSsl: Boolean,
        allowPublicKeyRetrieval: Boolean,
        connectTimeoutMs: Long,
        socketTimeoutMs: Long
    ): String = buildString {

        append("jdbc:mysql://").append(host).append(':').append(port).append('/').append(db)
        append("?characterEncoding=UTF-8")
        append("&connectionTimeZone=SERVER")
        // executeUpdateが「一致した行数」を返すようにする(既定値だが明示しておく)。
        // trueにすると同値更新で0行が返り、成功を失敗と誤判定してしまう
        append("&useAffectedRows=false")
        // タイムアウト未設定だと接続が黙って切れた際に永久にブロックし、
        // 単一スレッドで動く在庫処理が完全に停止する
        append("&connectTimeout=").append(connectTimeoutMs)
        append("&socketTimeout=").append(socketTimeoutMs)
        // 行ロック待ちは socketTimeout より先に切れなければならない。
        // socketTimeout が先に来ると「サーバー側で文が実行中かどうか分からない」状態になり、
        // ロールバックが保証される 1205 と違って再試行も判断もできなくなる
        append("&sessionVariables=innodb_lock_wait_timeout=").append(lockWaitSeconds(socketTimeoutMs))

        if (useSsl) {
            append("&sslMode=REQUIRED")
        } else {
            append("&sslMode=DISABLED")
            append("&allowPublicKeyRetrieval=").append(allowPublicKeyRetrieval)
        }
    }

    /** socketTimeoutより確実に短いロック待ち時間(秒)。socketTimeout=0(無制限)ならMySQLの既定に任せる */
    private fun lockWaitSeconds(socketTimeoutMs: Long): Long =
        if (socketTimeoutMs <= 0) 50 else ((socketTimeoutMs - 500) / 1000).coerceIn(1, 50)

    ////////////////////////////////
    //      起動時専用の接続
    ////////////////////////////////

    /**
     * プールとは別の、応答待ちが長い専用コネクションで実行する。
     *
     * 起動時のスキーマ移行は `ALTER TABLE ADD UNIQUE` や全件の重複統合を含み、
     * テーブルが大きいと通常の socketTimeout を超える。プールを使うと移行が
     * 中途半端に失敗してプラグインが起動しなくなるため、ここだけ別扱いにする。
     *
     * メインスレッドから起動時に一度だけ呼ぶ想定。
     */
    fun <T> withStartupConnection(label: String, block: (Connection) -> T): DbResult<T> {

        if (startupUrl.isEmpty()) {
            return DbResult.Failure("接続設定が初期化されていません")
        }

        return try {
            //DriverManagerはlibraries:で読まれたドライバを見つけられないことがあるため直接生成する
            val driver = Class.forName(DRIVER_CLASS).getDeclaredConstructor().newInstance() as java.sql.Driver

            val props = Properties().apply {
                setProperty("user", jdbcUser)
                setProperty("password", jdbcPassword)
            }

            driver.connect(startupUrl, props).use { con ->
                DbResult.Success(block(con))
            }
        } catch (e: Throwable) {
            plugin.logger.log(Level.SEVERE, "[$poolName] 起動時処理に失敗: $label", e)
            DbResult.Failure("起動時処理に失敗しました: ${rootMessage(e)}", e)
        }
    }

    ////////////////////////////////
    //      実行
    ////////////////////////////////

    /**
     * UPDATE / INSERT / DELETE。成功時の値は影響行数。
     * 「0行だった」も成功として返すため、呼び出し側で行数の確認が必要。
     */
    fun update(sql: String, vararg params: Any?): DbResult<Int> =
        execute(sql) { con -> con.executeUpdate(sql, *params) }

    fun update(statement: SqlStatement): DbResult<Int> =
        update(statement.sql, *statement.params.toTypedArray())

    /**
     * SELECT。mapperはコネクションが生きている間に呼ばれるので、
     * 必要な値をこの中で全て取り出すこと(ResultSetを外へ持ち出さない)。
     */
    fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): DbResult<T> =
        execute(sql) { con -> con.executeQuery(sql, *params, mapper = mapper) }

    /**
     * 複数のSQLをひとつのトランザクションで実行する。
     * blockが例外を投げた場合はロールバックする。
     */
    fun <T> transaction(label: String, block: (Connection) -> T): DbResult<T> =
        execute(label) { con ->
            con.autoCommit = false
            try {
                val result = block(con)
                con.commit()
                result
            } catch (e: Throwable) {
                runCatching { con.rollback() }
                    .onFailure { plugin.logger.log(Level.SEVERE, "[$poolName] ロールバックに失敗: $label", it) }
                throw e
            } finally {
                runCatching { con.autoCommit = true }
            }
        }

    private fun <T> execute(label: String, block: (Connection) -> T): DbResult<T> {

        val ds = dataSource
        if (ds == null || ds.isClosed) {
            val message = "接続プールが利用できません"
            plugin.logger.severe("[$poolName] $message: ${shorten(label)}")
            return DbResult.Failure(message)
        }

        var attempt = 0

        while (true) {

            // 1) コネクション取得。ここでの失敗はSQL未実行が確定しているので常に再試行できる
            val con = try {
                ds.connection
            } catch (e: SQLException) {
                if (attempt < maxRetry) {
                    attempt++
                    continue
                }
                logConnectionFailure(label, e)
                return DbResult.Failure("コネクションを取得できませんでした: ${rootMessage(e)}", e)
            }

            stackTraceLogged.set(false)

            // 2) SQL実行
            try {
                con.use { return DbResult.Success(block(it)) }
            } catch (e: SQLException) {
                if (e.errorCode in RETRYABLE_ERROR_CODES && attempt < maxRetry) {
                    attempt++
                    plugin.logger.warning(
                        "[$poolName] 一時的な競合(errorCode=${e.errorCode})、再試行 $attempt/$maxRetry: ${shorten(label)}"
                    )
                    continue
                }
                plugin.logger.log(
                    Level.SEVERE,
                    "[$poolName] SQLの実行に失敗 (errorCode=${e.errorCode} sqlState=${e.sqlState}): ${shorten(label)}",
                    e
                )
                return DbResult.Failure("SQLの実行に失敗しました: ${e.message}", e)
            } catch (e: Throwable) {
                plugin.logger.log(Level.SEVERE, "[$poolName] SQL実行中に予期しないエラー: ${shorten(label)}", e)
                return DbResult.Failure("予期しないエラー: ${e.message}", e)
            }
        }
    }

    ////////////////////////////////
    //      内部ユーティリティ
    ////////////////////////////////

    /**
     * 接続失敗のログ。原因の要点だけを毎回出し、スタックトレースは最初の1回だけにする。
     * DBが落ちている間はキューの件数だけ同じ例外が出るため、全文を毎回出すとログが埋まる。
     */
    private fun logConnectionFailure(label: String, e: Throwable) {

        val message = "[$poolName] コネクション取得に失敗 (${attemptsLabel()}): ${rootMessage(e)} / ${shorten(label)}"

        if (stackTraceLogged.compareAndSet(false, true)) {
            plugin.logger.log(Level.SEVERE, message, e)
        } else {
            plugin.logger.severe(message)
        }
    }

    private fun attemptsLabel(): String = "計${maxRetry + 1}回試行"

    /** 例外チェーンの根本原因のメッセージ。UnknownHostException などを埋もれさせない */
    private fun rootMessage(e: Throwable): String {

        var cause: Throwable = e
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!

        val name = cause::class.java.simpleName
        val text = cause.message?.trim().orEmpty()

        return if (text.isEmpty()) name else "$name: $text"
    }

    private fun readPort(raw: Any?): Int = when (raw) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull() ?: DEFAULT_PORT
        else -> DEFAULT_PORT
    }.let { if (it in 1..65535) it else DEFAULT_PORT }

    private fun shorten(sql: String): String =
        sql.trim().replace(Regex("\\s+"), " ").take(160)
}
