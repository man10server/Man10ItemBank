package red.man10.man10itembank.testsupport

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import org.mockito.Mockito
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * plugin.logger に差し込んで、出力されたログを検証できるようにする。
 *
 * 移行は「何をしたか」をログに残すことが復旧手段そのものなので、
 * 出力内容もテスト対象にする。
 */
class LogCapture {

    private val records = CopyOnWriteArrayList<LogRecord>()

    val logger: Logger = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        level = Level.ALL
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) {
                records.add(record)
            }

            override fun flush() = Unit
            override fun close() = Unit
        })
    }

    fun messages(): List<String> = records.mapNotNull { it.message }

    fun severeMessages(): List<String> =
        records.filter { it.level == Level.SEVERE }.mapNotNull { it.message }

    fun anyContains(text: String): Boolean = messages().any { it.contains(text) }

    fun dump(): String = records.joinToString("\n") { "[${it.level}] ${it.message}" }

    fun clear() = records.clear()
}

/**
 * Database が必要とする JavaPlugin を用意する。
 *
 * JavaPlugin は PluginClassLoader 以外から生成されると例外を投げるため継承では作れない。
 * 実際に使われるのは getConfig() と getLogger() だけなのでモックで足りる。
 */
class TestPluginContext(
    schema: String,
    user: String = MySqlSupport.APP_USER,
    password: String = MySqlSupport.APP_PASSWORD,
    configure: YamlConfiguration.() -> Unit = {}
) {

    val logs = LogCapture()

    val config: YamlConfiguration = YamlConfiguration().apply {

        set("mysql.host", MySqlSupport.host)
        set("mysql.port", MySqlSupport.port)
        set("mysql.db", schema)
        set("mysql.user", user)
        set("mysql.pass", password)

        set("mysql.useSsl", false)
        set("mysql.allowPublicKeyRetrieval", true)
        set("mysql.maxRetry", 2)
        set("mysql.autoMigrate", true)

        set("mysql.pool.maximumPoolSize", 4)
        set("mysql.pool.minimumIdle", 1)
        set("mysql.pool.connectionTimeoutMs", 5_000)
        set("mysql.pool.socketTimeoutMs", 15_000)
        set("mysql.pool.idleTimeoutMs", 300_000)
        set("mysql.pool.maxLifetimeMs", 1_200_000)
        set("mysql.pool.validationTimeoutMs", 3_000)
        set("mysql.pool.leakDetectionThresholdMs", 30_000)

        configure()
    }

    val plugin: JavaPlugin = Mockito.mock(JavaPlugin::class.java).also {
        Mockito.doReturn(config).`when`(it).config
        Mockito.doReturn(logs.logger).`when`(it).logger
    }
}
