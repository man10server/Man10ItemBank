package red.man10.man10itembank

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import red.man10.man10itembank.util.Utility
import java.util.concurrent.Executors

object Event : Listener{

    private val thread = Executors.newSingleThreadExecutor()

    @EventHandler
    fun pickupEvent(e:EntityPickupItemEvent){
        val p = e.entity
        if (p !is Player)return

        //許可してない人はリターン
        if (!Man10ItemBank.allowAutoCollectUsers.contains(p.uniqueId)){
            return
        }

        //ドロップアイテムの取得
        val item = e.item.itemStack
        val data = ItemData.getItemData(item)?:return
        val amount = item.amount
        e.item.remove()
        e.isCancelled = true

        //アイテムを追加
//        ItemData.addItemAmount(p.uniqueId,p.uniqueId,data.id,amount)
        ItemData.addCacheItemAmount(p.uniqueId,data.id,amount)
    }

    @EventHandler
    fun login(e:PlayerJoinEvent){

        val p = e.player
        if (!Utility.hasCollectPermission(p))return

        thread.execute {
            Thread.sleep(3000)
            val ret = if (Man10ItemBank.allowAutoCollectUsers.contains(p.uniqueId)) "有効" else "無効"
            Utility.sendMsg(p,"アイテムバンクの自動保存は${ret}です")
        }
    }

    @EventHandler
    fun logout(e:PlayerQuitEvent){
        val p = e.player
        ItemData.commitCachedItemBank(p.uniqueId)
    }

}