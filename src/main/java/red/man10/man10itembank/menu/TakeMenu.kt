package red.man10.man10itembank.menu

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction
import red.man10.man10itembank.ItemData
import red.man10.man10itembank.Log
import red.man10.man10itembank.Man10ItemBank
import red.man10.man10itembank.util.Utility

class TakeMenu(p:Player,page:Int) : MenuFramework(p,54,"アイテムを取り出す"){

    init {

        val itemIndex = ItemData.getItemIndexMap().values.toList()

        var inc = 0

        while (menu.getItem(44)==null){

            val index = inc+page*45
            inc++
            if (itemIndex.size<=index) break

            val data = itemIndex[index]
            val button = Button(data.item!!.type,"ItemIndex:${data.id}")
            button.displayName(data.itemKey)

            button.setClickAction{e,->
                if (e.action == InventoryAction.PICKUP_ALL){
                    takeItemFromItemStorage(p,data.id,1,page)
                }

                if (e.action == InventoryAction.MOVE_TO_OTHER_INVENTORY){
                    takeItemFromItemStorage(p,data.id,64,page)
                }
            }

            //評価額、個数を確認
            ItemData.getItemAmount(p.uniqueId,data.id){

                Bukkit.getScheduler().runTask(Man10ItemBank.plugin, Runnable {
                    if (it>0){
                        button.setEnchant()
                    }

                    button.lore(mutableListOf(
                        "§a所有数:§e${Utility.format(it)}",
                        "§a単価:§e${Utility.format(data.price)}",
                        "§a推定評価額:§e${Utility.format(data.price*it.toDouble())}"))
                    menu.addItem(button.icon())
                })
            }
        }

        //previous
        if (page!=0){
            val previous = Button(Material.PAPER,"previous")
            previous.displayName("前のページへ")
            previous.setClickAction{
                TakeMenu(p,page-1).open()
            }
            setButton(previous,45)
        }

        //next
        if (menu.getItem(44) != null){
            val next = Button(Material.PAPER,"next")
            next.displayName("次のページへ")
            next.setClickAction{
                TakeMenu(p,page+1).open()
            }
            setButton(next,45)
        }


    }

    private fun takeItemFromItemStorage(p:Player, id:Int, amount:Int, page:Int){

        ItemData.takeItemAmount(p.uniqueId,p.uniqueId,id,amount){
            if (it==-2){
                Utility.sendError(p,"在庫ないので取り出すことができません")
                return@takeItemAmount
            }

            if (p.inventory.firstEmpty()==-1){
                Utility.sendError(p,"インベントリがいっぱいなので取り出すことができません")
                Log.storageLog(p.uniqueId,p.uniqueId,id,amount,"FailedTakingItem(FullOfInventory)")
                ItemData.addItemAmount(p.uniqueId,p.uniqueId,id,amount)
                return@takeItemAmount
            }

            val data = ItemData.getItemData(id)!!
            Utility.sendMsg(p,"${amount}個取り出しました(現在の在庫:${Utility.format(it)})")

            val clone = data.item!!.clone()

            clone.amount = amount

            p.inventory.addItem(clone)


        }


    }

}