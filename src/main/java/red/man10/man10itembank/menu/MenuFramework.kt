package red.man10.man10itembank.menu

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

open class MenuFramework(menuSize: Int, title: String) {

    var menu : Inventory

    init {
        menu = Bukkit.createInventory(null,menuSize, text(title))
    }

    fun open(p:Player){
        p.openInventory(menu)
    }

    //slotは0スタート
    fun setButton(button: Button,slot:Int){
        menu.setItem(slot,button.icon())
    }

    //背景として全埋めする
    fun fill(button:Button){
        for (i in 0 until menu.size){
            setButton(button,i)
        }
    }

    class Button(icon:Material,val key:String){

        private var buttonItem : ItemStack
        private var actionData : ClickAction? = null

        init {
            buttonItem = ItemStack(icon)
            val meta = buttonItem.itemMeta
            meta.persistentDataContainer.set(NamespacedKey.fromString("key")!!
                , PersistentDataType.STRING,key)
            buttonItem.itemMeta = meta
        }

        companion object{

            private val buttonMap = HashMap<String,Button>()

            fun set(button:Button){
                buttonMap[button.key] = button
            }

            fun get(item:ItemStack):Button?{

                if (!item.hasItemMeta())return null

                val meta = item.itemMeta
                val key = meta.persistentDataContainer[NamespacedKey.fromString("key")!!, PersistentDataType.STRING]?:return null

                return buttonMap[key]
            }
        }

        fun displayName(text:String):Button{
            val meta = buttonItem.itemMeta
            meta.displayName(text(text))
            buttonItem.itemMeta = meta
            set(this)
            return this
        }

        fun lore(lore:List<String>):Button{
            val loreComponent = mutableListOf<Component>()
            lore.forEach { loreComponent.add(text(it)) }

            val meta = buttonItem.itemMeta
            meta.lore(loreComponent)
            buttonItem.itemMeta = meta
            set(this)
            return this

        }

        fun setClickAction(action: ClickAction):Button{
            actionData = action
            set(this)
            return this
        }

        fun click(e:InventoryClickEvent){
            if (actionData == null)return
            actionData!!.action(e.whoClicked as Player,e)
        }

        fun icon():ItemStack{
            return buttonItem
        }

        fun interface ClickAction{
            fun action(p:Player,e:InventoryClickEvent)
        }
    }

    object ButtonListener:Listener{

        @EventHandler
        fun clickEvent(e:InventoryClickEvent){

            if (e.whoClicked !is Player)return
            val item = e.currentItem?:return
            val data = Button.get(item)?:return
            e.isCancelled = true
            data.click(e)
        }

    }
}

