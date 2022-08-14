package red.man10.man10itembank

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import red.man10.man10itembank.util.MySQLManager.Companion.mysqlQueue
import java.util.*

object Log {

    //システムログ
    fun systemLog(uuid:UUID?,action:String){

        val p : OfflinePlayer? = if (uuid!=null) Bukkit.getOfflinePlayer(uuid) else null

        mysqlQueue.add("INSERT INTO system_log (player, uuid, action, time) " +
                "VALUES ('${p?.name?:"Server"}', '${uuid}', '${action}', DEFAULT)")
    }

    //mibをいじった時にログをとる orderがnullの時は、鯖がmibを編集した場合
    fun storageLog(order:UUID?, target:UUID, id:Int, editAmount:Int,storageAmount:Int, action: String){

        var orderName = "Server"
        var orderPlayer : Player? = null

        val targetName : String = Bukkit.getOfflinePlayer(target).name!!

        if (order!=null){
            val offlinePlayer = Bukkit.getOfflinePlayer(order)
            if (offlinePlayer.isOnline){ orderPlayer = offlinePlayer.player!! }
            orderName = offlinePlayer.name!!
        }

        mysqlQueue.add("INSERT INTO storage_log (item_id, item_key, order_player, order_uuid, target_player, target_uuid, action, edit_amount, storage_amount, world, x, y, z, time) " +
                "VALUES (${id}, " +
                "'${ItemData.getItemData(id)!!.itemKey}', " +
                "'${orderName}', " +
                "'${order}', " +
                "'${targetName}', " +
                "'${target}', " +
                "'${action}', " +
                "${editAmount}, " +
                "${storageAmount}, " +
                "'${orderPlayer?.location?.world?.name?:"none"}', " +
                "${orderPlayer?.location?.x?:0.0}, " +
                "${orderPlayer?.location?.y?:0.0}, " +
                "${orderPlayer?.location?.z?:0.0}, " +
                "DEFAULT)")
    }


}