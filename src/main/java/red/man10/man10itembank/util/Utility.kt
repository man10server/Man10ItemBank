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
    fun itemFromBase64(data: String): ItemStack? = try {
        val inputStream = ByteArrayInputStream(Base64Coder.decodeLines(data))
        val dataInput = BukkitObjectInputStream(inputStream)
        val items = arrayOfNulls<ItemStack>(dataInput.readInt())

        // Read the serialized inventory
        for (i in items.indices) {
            items[i] = dataInput.readObject() as ItemStack
        }

        dataInput.close()
        items[0]
    } catch (e: Exception) {
        null
    }

    @Throws(IllegalStateException::class)
    fun itemToBase64(item: ItemStack): String {
        try {
            val outputStream = ByteArrayOutputStream()
            val dataOutput = BukkitObjectOutputStream(outputStream)
            val items = arrayOfNulls<ItemStack>(1)
            items[0] = item
            dataOutput.writeInt(items.size)

            for (i in items.indices) {
                dataOutput.writeObject(items[i])
            }

            dataOutput.close()

            return Base64Coder.encodeLines(outputStream.toByteArray())

        } catch (e: Exception) {
            throw IllegalStateException("Unable to save item stacks.", e)
        }
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