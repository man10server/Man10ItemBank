package red.man10.man10itembank.testsupport

import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * テスト全体で1つだけ立てるMySQL。
 *
 * テストごとにスキーマ(データベース)を作り直すことで、
 * 「まっさらな状態から移行を走らせる」という条件を毎回再現する。
 */
object MySqlSupport {

    const val APP_USER = "itembank"
    const val APP_PASSWORD = "itembank"

    /** ALTER/INDEX を持たないユーザー。移行が権限不足で失敗する経路の確認に使う */
    const val LIMITED_USER = "limited"
    const val LIMITED_PASSWORD = "limited"

    /**
     * MySQLContainer.configure() が MYSQL_ROOT_PASSWORD を自身の password で上書きするため、
     * withEnv では変更できない。アプリ用ユーザーと同じ値になる。
     */
    private const val ROOT_PASSWORD = APP_PASSWORD

    private val schemaCounter = AtomicInteger()

    /**
     * 初期化処理の中からこのプロパティ(や host/port)を参照しないこと。
     * lazy は再入するとイニシャライザを再実行するため、コンテナが際限なく増える。
     * 起動直後に必要な処理へは、コンテナの参照を引数で渡す。
     */
    private val started: MySQLContainer<*> by lazy {
        MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("bootstrap")
            .withUsername(APP_USER)
            .withPassword(APP_PASSWORD)
            // MySQL 8系の既定は caching_sha2_password。非TLSで繋ぐ場合これが無いと
            // 起動待ちのJDBC接続が認証に失敗し、Testcontainersがコンテナを落として再試行し続ける
            .withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            // ロック待ちタイムアウト(1205)の再試行を短時間で再現できるようにする
            .withCommand("--innodb-lock-wait-timeout=1")
            .also {
                it.start()
                createLimitedUser(it)
            }
    }

    val container: MySQLContainer<*> get() = started

    val host: String get() = started.host

    val port: Int get() = started.getMappedPort(MySQLContainer.MYSQL_PORT)

    /**
     * 空のスキーマを作り、アプリ用ユーザーに権限を与えて名前を返す。
     */
    fun createSchema(prefix: String): String {

        val name = "${prefix.lowercase()}_${schemaCounter.incrementAndGet()}"

        rootConnection().use { con ->
            con.createStatement().use { st ->
                st.execute("CREATE DATABASE `$name` DEFAULT CHARACTER SET utf8mb4")
                st.execute("GRANT ALL PRIVILEGES ON `$name`.* TO '$APP_USER'@'%'")
                //ALTER/INDEX を意図的に外す
                st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON `$name`.* TO '$LIMITED_USER'@'%'")
                st.execute("FLUSH PRIVILEGES")
            }
        }

        return name
    }

    fun appConnection(schema: String): Connection = connect(schema, APP_USER, APP_PASSWORD)

    fun connect(schema: String, user: String, password: String): Connection =
        DriverManager.getConnection(
            "jdbc:mysql://$host:$port/$schema?sslMode=DISABLED&allowPublicKeyRetrieval=true",
            user,
            password
        )

    private fun rootConnection(): Connection = rootConnection(started)

    /** lazy初期化中にも呼べるよう、コンテナを引数で受け取る */
    private fun rootConnection(target: MySQLContainer<*>): Connection =
        DriverManager.getConnection(
            "jdbc:mysql://${target.host}:${target.getMappedPort(MySQLContainer.MYSQL_PORT)}/" +
                    "?sslMode=DISABLED&allowPublicKeyRetrieval=true",
            "root",
            ROOT_PASSWORD
        )

    private fun createLimitedUser(target: MySQLContainer<*>) {
        rootConnection(target).use { con ->
            con.createStatement().use { st ->
                st.execute("CREATE USER '$LIMITED_USER'@'%' IDENTIFIED BY '$LIMITED_PASSWORD'")
            }
        }
    }

    ////////////////////////////////
    //      スキーマ投入
    ////////////////////////////////

    /** jarに同梱される db.sql。現行(UNIQUE済み)のスキーマ */
    fun currentSchemaSql(): String = readResource("/db.sql")

    /**
     * 移行前のスキーマ。db.sql の UNIQUE インデックスを旧来の非UNIQUEに戻したもの。
     * db.sql を書き換えたらここで検出できるよう、置換できなければ失敗させる。
     */
    fun legacySchemaSql(): String {

        val current = currentSchemaSql()

        val unique = "create unique index item_storage_uuid_item_id_uindex\n\ton item_storage (uuid, item_id);"
        val legacy = "create index item_storage_uuid_item_id_index\n\ton item_storage (uuid, item_id);"

        check(current.contains(unique)) {
            "db.sql の item_storage UNIQUE インデックス定義が変わっています。legacySchemaSql を更新してください"
        }

        return current.replace(unique, legacy)
    }

    fun applySql(schema: String, sql: String, user: String = APP_USER, password: String = APP_PASSWORD) {
        connect(schema, user, password).use { con ->
            con.createStatement().use { st ->
                splitStatements(sql).forEach { st.execute(it) }
            }
        }
    }

    /** `--` 行コメントを落として `;` で分割する。db.sql 程度の単純なSQLのみが対象 */
    private fun splitStatements(sql: String): List<String> =
        sql.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun readResource(path: String): String =
        MySqlSupport::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("$path が見つかりません")

    ////////////////////////////////
    //      検証用ヘルパ
    ////////////////////////////////

    fun <T> queryOne(schema: String, sql: String, mapper: (java.sql.ResultSet) -> T): T =
        appConnection(schema).use { con ->
            con.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    check(rs.next()) { "行が返りませんでした: $sql" }
                    mapper(rs)
                }
            }
        }

    fun <T> queryAll(schema: String, sql: String, mapper: (java.sql.ResultSet) -> T): List<T> =
        appConnection(schema).use { con ->
            con.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val list = mutableListOf<T>()
                    while (rs.next()) list.add(mapper(rs))
                    list
                }
            }
        }

    fun execute(schema: String, vararg sql: String) {
        appConnection(schema).use { con ->
            con.createStatement().use { st -> sql.forEach { st.execute(it) } }
        }
    }

    fun insertStorage(schema: String, uuid: String, itemId: Int, amount: Int, itemKey: String = "test_item") {
        appConnection(schema).use { con ->
            con.prepareStatement(
                "INSERT INTO item_storage (player, uuid, item_id, item_key, amount, time) " +
                        "VALUES (?, ?, ?, ?, ?, now())"
            ).use { ps ->
                ps.setString(1, uuid.take(16))
                ps.setString(2, uuid)
                ps.setInt(3, itemId)
                ps.setString(4, itemKey)
                ps.setInt(5, amount)
                ps.executeUpdate()
            }
        }
    }
}
