package red.man10.man10itembank

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent

object Event : Listener{

    @EventHandler
    fun pickupEvent(e:EntityPickupItemEvent){
        val p = e.entity
        if (p !is Player)return

        //拒否しているユーザーは処理しない
        if (Man10ItemBank.denyAutoCollectUsers.contains(p.uniqueId)){
            return
        }

        //ドロップアイテムの取得
        val item = e.item.itemStack
        val data = ItemData.getItemData(item)?:return
        val amount = item.amount
        e.item.remove()
        e.isCancelled = true

        //アイテムを追加
        ItemData.addItemAmount(p.uniqueId,p.uniqueId,data.id,amount)
    }


}