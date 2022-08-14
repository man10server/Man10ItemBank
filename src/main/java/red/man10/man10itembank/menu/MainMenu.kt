package red.man10.man10itembank.menu

import org.bukkit.Material
import org.bukkit.entity.Player
import red.man10.man10itembank.util.Utility.sendMsg

class MainMenu(p:Player) : MenuFramework(p,36, "§f§lアイテムバンク") {

    init {
        val pane = Button(Material.LIGHT_BLUE_STAINED_GLASS_PANE)
        pane.displayName("")

        fill(pane)

        val putItemButton = Button(Material.CHEST)
        putItemButton.displayName("§a§lアイテムをアイテムバンクにしまう")

        putItemButton.setClickAction { e ->
            PutMenu(p).open()
        }

        arrayOf(10,11,12,19,20,21).forEach { setButton(putItemButton,it) }

        val takeItemButton = Button(Material.DISPENSER)
        takeItemButton.displayName("§a§lアイテムをアイテムバンクから取り出す")

        takeItemButton.setClickAction { e ->
            TakeMenu(p,0).open()
        }

        arrayOf(14,15,16,23,24,25).forEach { setButton(takeItemButton,it) }

    }

}