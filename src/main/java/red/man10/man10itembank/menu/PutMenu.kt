package red.man10.man10itembank.menu

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import red.man10.man10itembank.ItemData
import red.man10.man10itembank.Man10ItemBank
import red.man10.man10itembank.StockResult
import red.man10.man10itembank.util.Utility.format
import red.man10.man10itembank.util.Utility.sendError
import red.man10.man10itembank.util.Utility.sendMsg

class PutMenu(p:Player) : MenuFramework(p,54,"アイテムを保存する"){

    override fun init() {
        val putButton = Button(Material.RED_STAINED_GLASS_PANE)
        putButton.title("§c§l保存")

        putButton.setClickAction{e ->
            putItemToItemStorage(p,e.inventory)
        }

        arrayOf(45,46,47,48,49,50,51,52,53).forEach { setButton(putButton,it) }

        setCloseAction{e->
            putItemToItemStorage(p,e.inventory)
        }

        setClickAction{e ->
            val item = e.currentItem?:return@setClickAction
            if (ItemData.getItemData(item) == null){
                e.isCancelled = true
            }
        }
    }

    private fun putItemToItemStorage(p:Player, menu:Inventory){

        val putData = HashMap<Int,Int>()

        for (i in 0 until 45){

            val item = menu.getItem(i)?:continue
            val data = ItemData.getItemData(item)?:continue

            val amount = putData[data.id]?:0

            putData[data.id] = amount+item.amount

            item.amount = 0
        }

        putData.forEach {
            ItemData.changeItemAmount(p.uniqueId,p.uniqueId,it.key,it.value,"PutItem"){ result ->

                val data = ItemData.getItemData(it.key)?:return@changeItemAmount

                when(result){

                    is StockResult.Updated ->
                        sendMsg(p,"${data.itemKey}を${format(it.value)}個追加しました(現在の在庫:${format(result.amount)}個)")

                    //現物はすでにメニューから消しているため、書き込めなかった分は必ず返す
                    is StockResult.Insufficient -> {
                        sendError(p,"${data.itemKey}は保有上限を超えるため保存できませんでした")
                        giveBack(data,it.value)
                    }

                    is StockResult.Error -> {
                        sendError(p,"${data.itemKey}の保存に失敗しました。アイテムを返却します")
                        giveBack(data,it.value)
                    }
                }
            }
        }

    }

    /**
     * DBへ書き込めなかったアイテムを現物で返す。
     * 旧実装はDB書き込みの成否を見ずにメニューからアイテムを消していたため、失敗＝アイテム消失だった。
     */
    private fun giveBack(data: ItemData.ItemIndex, amount: Int){

        val template = data.item ?: return

        Bukkit.getScheduler().runTask(Man10ItemBank.plugin, Runnable {

            if (!p.isOnline){
                //返却先が居ないので、手動で復旧できるようログに残す
                Man10ItemBank.plugin.logger.severe(
                    "返却できませんでした player=${p.name} uuid=${p.uniqueId} " +
                            "item_key=${data.itemKey} amount=$amount")
                return@Runnable
            }

            var rest = amount
            val max = template.maxStackSize.coerceAtLeast(1)

            while (rest > 0){

                val stack = template.clone()
                stack.amount = minOf(rest, max)
                rest -= stack.amount

                //入り切らない分は足元に落とす
                p.inventory.addItem(stack).values.forEach { leftover ->
                    p.world.dropItem(p.location, leftover)
                }
            }
        })
    }

}
