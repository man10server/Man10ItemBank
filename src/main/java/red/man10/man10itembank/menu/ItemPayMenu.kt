package red.man10.man10itembank.menu

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction
import red.man10.man10itembank.ItemData
import red.man10.man10itembank.Man10ItemBank
import red.man10.man10itembank.util.Utility

class ItemPayMenu(p:Player,page:Int) : MenuFramework(p,54,"§a§l送るアイテムを選択"){

    init {

        //アイテムを入れられないように
        setClickListener{ it.isCancelled = true }

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
                p.performCommand("ipay c1 ${data.itemKey}")
                p.closeInventory()
            }

            //評価額、個数を確認
            ItemData.getItemAmount(p.uniqueId,data.id){

                button.enchant(it>0)
                button.lore(mutableListOf("§a所有数:§e${Utility.format(it)}"))

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
            previous.setClickAction{ ItemPayMenu(p,page-1).open() }
            arrayOf(45,46,47).forEach { setButton(previous,it) }

        }

        //next
        if (inc>=44){
            val next = Button(Material.RED_STAINED_GLASS_PANE)
            next.title("次のページへ")
            next.setClickAction{ ItemPayMenu(p,page+1).open() }
            arrayOf(51,52,53).forEach { setButton(next,it) }
        }


    }

}