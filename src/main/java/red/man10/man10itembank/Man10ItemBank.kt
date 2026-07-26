package red.man10.man10itembank

import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10itembank.menu.MenuFramework
import red.man10.man10itembank.util.Database
import java.util.UUID

class Man10ItemBank : JavaPlugin() {

    companion object {
        lateinit var plugin: JavaPlugin
        var allowAutoCollectUsers = mutableListOf<UUID>()
    }

    private var database: Database? = null

    override fun onEnable() {
        // Plugin startup logic

        plugin = this

        saveDefaultConfig()

        val db = Database(this, "Man10ItemBank")

        // DBが使えない状態で動かすとアイテムの消失・複製に直結するため、ここで起動を止める
        if (!db.connect()) {
            logger.severe("MySQLに接続できないためMan10ItemBankを無効化します。config.ymlを確認してください")
            server.pluginManager.disablePlugin(this)
            return
        }

        database = db

        Log.start(this, db)

        if (!ItemData.start(this, db)) {
            logger.severe("ItemIndexを読み込めないためMan10ItemBankを無効化します")
            server.pluginManager.disablePlugin(this)
            return
        }

        MenuFramework.setup(this)

        getCommand("mib")!!.setExecutor(Command)
        getCommand("mibop")!!.setExecutor(Command)
        getCommand("ipay")!!.setExecutor(Command)

        server.pluginManager.registerEvents(MenuFramework.MenuListener, this)
        server.pluginManager.registerEvents(Event, this)
    }

    override fun onDisable() {
        // Plugin shutdown logic

        // サーバー停止時、PlayerQuitEventはプラグイン無効化より後に発火することがあるため
        // 自動回収キャッシュはここで明示的に流す
        ItemData.flushAllCaches()

        // キューを処理し切ってからプールを閉じる(順序が逆だと未処理分が全て失敗する)
        ItemData.shutdown()
        Log.shutdown()
        Event.shutdown()

        database?.shutdown()
        database = null
    }
}
