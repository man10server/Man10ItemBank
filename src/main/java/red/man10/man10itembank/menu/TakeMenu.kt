package red.man10.man10itembank.menu

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction
import red.man10.man10itembank.ItemData
import red.man10.man10itembank.Log
import red.man10.man10itembank.Man10ItemBank
import red.man10.man10itembank.StockResult
import red.man10.man10itembank.util.Utility

class TakeMenu(p:Player,private val page : Int) : MenuFramework(p,54,"アイテムを取り出す"){

    override fun init() {

        //アイテムを入れられないように
        setClickAction(){ it.isCancelled = true }

        val itemIndex = ItemData.getItemIndexMap().values.toList()

        var inc = 0

        while (menu.getItem(44)==null){

            val index = inc+page*45
            inc++
            if (itemIndex.size<=index) break

            val data = itemIndex[index]
            val button = Button(data.item!!.type)
            button.title("§b${data.itemKey}")
            if (data.item!!.hasItemMeta() && data.item!!.itemMeta.hasCustomModelData()){
                button.cmd(data.item!!.itemMeta.customModelData)
            }

            button.setClickAction{e,->

                if (e.action == InventoryAction.PICKUP_ALL){
                    takeItemFromItemStorage(p,data.id,1)
                }

                if (e.action == InventoryAction.MOVE_TO_OTHER_INVENTORY){
                    takeItemFromItemStorage(p,data.id,data.item!!.maxStackSize)
                }

                //評価額、個数を確認
                ItemData.getItemAmount(p.uniqueId,data.id){

                    button.enchant(it>0)

                    button.lore(mutableListOf(
                        "§a所有数:§e${Utility.format(it)}",
                        "§a単価:§e${Utility.format(data.bid)}",
                        "§a推定評価額:§e${Utility.format(data.bid*it.toDouble())}"))

                    Bukkit.getScheduler().runTask(Man10ItemBank.plugin, Runnable { setButton(button,e.slot) })
                }

            }

            //評価額、個数を確認
            ItemData.getItemAmount(p.uniqueId,data.id){

                button.enchant(it>0)

                button.lore(mutableListOf(
                    "§a所有数:§e${Utility.format(it)}",
                    "§a単価:§e${Utility.format(data.bid)}",
                    "§a推定評価額:§e${Utility.format(data.bid*it.toDouble())}"))

                Bukkit.getScheduler().runTask(Man10ItemBank.plugin, Runnable {
                    menu.addItem(button.icon())
                })
            }
        }

        //Back
        val back = Button(Material.LIGHT_BLUE_STAINED_GLASS_PANE)
        back.title("")
        arrayOf(45,46,47,48,49,50,51,52,53).forEach { setButton(back,it) }

        //previous
        if (page!=0){
            val previous = Button(Material.RED_STAINED_GLASS_PANE)
            previous.title("前のページへ")
            previous.setClickAction{ TakeMenu(p,page-1).open() }
            arrayOf(45,46,47).forEach { setButton(previous,it) }

        }

        //next
        if (inc>=44){
            val next = Button(Material.RED_STAINED_GLASS_PANE)
            next.title("次のページへ")
            next.setClickAction{ TakeMenu(p,page+1).open() }
            arrayOf(51,52,53).forEach { setButton(next,it) }
        }


    }

    private fun takeItemFromItemStorage(p:Player, id:Int, amount:Int){

        ItemData.changeItemAmount(p.uniqueId,p.uniqueId,id,-amount,"TakeItem"){ result ->

            when(result){

                is StockResult.Insufficient ->
                    Utility.sendError(p,"在庫ないので取り出すことができません")

                //DB障害。減算できていないので現物を渡してはいけない
                is StockResult.Error ->
                    Utility.sendError(p,"DBエラーのため取り出せませんでした。時間をおいて試してください")

                is StockResult.Updated -> {

                    val data = ItemData.getItemData(id)

                    if (data?.item == null){
                        returnToBank(p,id,amount,"NoItemData")
                        return@changeItemAmount
                    }

                    dispatch(Man10ItemBank.plugin){ giveItem(p,data,id,amount,result.amount) }
                }
            }
        }
    }

    //メインスレッドで実行する
    private fun giveItem(p:Player, data:ItemData.ItemIndex, id:Int, amount:Int, stock:Int){

        if (!p.isOnline){
            returnToBank(p,id,amount,"Offline")
            return
        }

        val template = data.item!!
        val max = template.maxStackSize.coerceAtLeast(1)

        var rest = amount
        var failed = 0

        while (rest > 0){

            val stack = template.clone()
            stack.amount = minOf(rest,max)
            rest -= stack.amount

            //旧実装はaddItemの戻り値(入り切らなかった分)を捨てており、そのまま消えていた
            p.inventory.addItem(stack).values.forEach { failed += it.amount }
        }

        if (failed > 0){
            Utility.sendError(p,"インベントリがいっぱいなので${Utility.format(failed)}個は取り出せませんでした")
            returnToBank(p,id,failed,"FullOfInventory")
        }

        val given = amount - failed

        if (given > 0){
            Utility.sendMsg(p,"${Utility.format(given)}個取り出しました(現在の在庫:${Utility.format(stock+failed)})")
        }
    }

    //渡せなかった分を倉庫へ戻す
    private fun returnToBank(p:Player, id:Int, amount:Int, reason:String){

        //在庫は動かない記録用の行。storage_amountは不明なのでnull(0を入れると集計が壊れる)
        Log.storageLog(p.uniqueId,p.uniqueId,id,0,null,"FailedTakingItem($reason)")

        ItemData.changeItemAmount(p.uniqueId,p.uniqueId,id,amount,"ReturnItem"){ result ->
            if (result !is StockResult.Updated){
                Man10ItemBank.plugin.logger.severe(
                    "取り出せなかった分を倉庫へ戻せませんでした " +
                            "player=${p.name} uuid=${p.uniqueId} item_id=$id amount=$amount reason=$reason")
            }
        }
    }

}
