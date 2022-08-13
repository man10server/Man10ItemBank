package red.man10.man10itembank

import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10itembank.util.MySQLManager

class Man10ItemBank : JavaPlugin() {
    override fun onEnable() {
        // Plugin startup logic
        getCommand("mib")!!.setExecutor(Command)

        MySQLManager.runAsyncMySQLQueue(this,"Man10ItemBank")
    }

    override fun onDisable() {
        // Plugin shutdown logic
    }
}