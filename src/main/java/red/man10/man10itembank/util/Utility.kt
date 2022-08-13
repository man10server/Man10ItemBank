package red.man10.man10itembank.util

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

object Utility {

    private const val prefix = ""

    fun sendMsg(p:Player,text:String){
        p.sendMessage(prefix +text)
    }

    fun sendMsg(sender:CommandSender,text: String){
        sender.sendMessage(prefix +text)
    }

    fun log(text: String){
        Bukkit.getLogger().info(prefix +text)
    }


}