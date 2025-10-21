package red.man10.man10itembank.util

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder
import red.man10.man10itembank.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

object Utility {

    const val prefix = "§4§l[§2§lMarket§4§l]§f§r"

    fun sendMsg(p:Player,text:String){
        p.sendMessage(prefix +text)
    }

    fun sendMsg(sender:CommandSender,text: String){
        sender.sendMessage(prefix +text)
    }

    fun sendError(p:Player,text: String){
        p.sendMessage("${prefix}§c§l${text}")
    }

    fun sendError(sender:CommandSender, text: String){
        sender.sendMessage("${prefix}§c§l${text}")
    }

    fun log(text: String,player: Player? = null){
//        Bukkit.getLogger().info(prefix +text)
        Log.systemLog(player?.uniqueId,text)
    }

    ///////////////////////////////
    //base 64
    //////////////////////////////
    fun itemFromBase64(data: String): ItemStack? {
        val bytes = Base64.getDecoder().decode(data)
        return ItemStack.deserializeBytes(bytes)
    }

    fun itemToBase64(item: ItemStack): String {
        val bytes = item.serializeAsBytes()
        return Base64.getEncoder().encodeToString(bytes)
    }
    fun hasUserPermission(p:Player):Boolean{
        if (!p.hasPermission("market.user")){
            sendError(p,"あなたには権限がありません！")
            return false
        }
        return true
    }

    fun hasCollectPermission(p:Player):Boolean{
        return p.hasPermission("market.collect")
    }

    fun hasOPPermission(p:Player):Boolean{
        if (!p.hasPermission("market.op")){
            sendError(p,"あなたには権限がありません！")
            return false
        }
        return true
    }

    fun format(double: Double):String{
        return String.format("%,.0f",double)
    }

    fun format(int: Int):String{
        return String.format("%,d",int)
    }

}