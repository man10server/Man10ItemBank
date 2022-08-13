package red.man10.man10itembank.menu

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import red.man10.man10itembank.util.Utility.prefix
import java.util.concurrent.ConcurrentHashMap

open class MenuFramework(val p:Player,menuSize: Int, title: String) {

    var menu : Inventory
    private var closeAction : OnCloseListener? = null

    init {
        menu = Bukkit.createInventory(null,menuSize, text(prefix+title))
    }

    companion object{
        private val menuMap = HashMap<Player,MenuFramework>()

        fun set(p:Player,menu:MenuFramework){
            menuMap[p] = menu
        }

        fun get(p:Player):MenuFramework?{
            return menuMap[p]
        }

        fun delete(p:Player){
            menuMap.remove(p)
        }
    }

    fun open(){
        p.closeInventory()
        set(p,this)
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

    fun setCloseListener(action:OnCloseListener){
        closeAction = action
    }

    fun close(e:InventoryCloseEvent){
        delete(e.player as Player)
        closeAction?.closeAction(e.player as Player,e)
    }

    fun interface OnCloseListener{
        fun closeAction(p:Player,e: InventoryCloseEvent)
    }

    class Button(icon:Material,val key:String){

        private var buttonItem : ItemStack
        private var actionData : OnClickListener? = null

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

        fun setClickAction(action: OnClickListener):Button{
            actionData = action
            set(this)
            return this
        }

        fun setEnchant(): Button {
            val meta = buttonItem.itemMeta
            meta.addEnchant(Enchantment.LUCK,1,false)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            buttonItem.itemMeta = meta
            set(this)
            return this
        }


        fun click(e:InventoryClickEvent){
            if (actionData == null)return
            actionData!!.action(e)
        }

        fun icon():ItemStack{
            return buttonItem
        }

        fun interface OnClickListener{
            fun action(e:InventoryClickEvent)
        }
    }

    object MenuListener:Listener{

        @EventHandler
        fun clickEvent(e:InventoryClickEvent){

            if (e.whoClicked !is Player)return
            val item = e.currentItem?:return
            val data = Button.get(item)?:return
            e.isCancelled = true
            data.click(e)
        }

        @EventHandler
        fun closeEvent(e:InventoryCloseEvent){

            if (e.player !is Player)return
            val menu = get(e.player as Player)?:return
            menu.close(e)
        }

    }
}

