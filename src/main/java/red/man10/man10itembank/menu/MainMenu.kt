package red.man10.man10itembank.menu

import org.bukkit.Material
import red.man10.man10itembank.util.Utility
import red.man10.man10itembank.util.Utility.sendMsg

object MainMenu : MenuFramework(36, "${Utility.prefix}§f§lアイテムバンク") {

    init {
        val pane = Button(Material.LIGHT_BLUE_STAINED_GLASS_PANE,"Back")
        pane.displayName("")

        fill(pane)

        val addItemButton = Button(Material.CHEST,"addItem")
        addItemButton.displayName("§a§lアイテムをアイテムバンクにしまう")

        addItemButton.setClickAction { p, e ->
            sendMsg(p,"アイテムバンクにしまう")

        }

        arrayOf(10,11,12,19,20,21).forEach { setButton(addItemButton,it) }

        val takeItemButton = Button(Material.DISPENSER,"takeItem")
        takeItemButton.displayName("§a§lアイテムをアイテムバンクから取り出す")

        takeItemButton.setClickAction { p, e ->
            sendMsg(p,"アイテムバンクから取り出す")

        }

        arrayOf(14,15,16,23,24,25).forEach { setButton(takeItemButton,it) }

        setCloseListener{ p, e ->
            sendMsg(p,"アイテムバンクを閉じた")
        }

    }

}