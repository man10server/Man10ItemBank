package red.man10.man10itembank

import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10itembank.util.MySQLManager

class Man10ItemBank : JavaPlugin() {

    companion object{
        lateinit var plugin : JavaPlugin
    }

    override fun onEnable() {
        // Plugin startup logic

        plugin = this

        getCommand("mib")!!.setExecutor(Command)

        MySQLManager.runAsyncMySQLQueue(this,"Man10ItemBank")
    }

    override fun onDisable() {
        // Plugin shutdown logic
    }
}