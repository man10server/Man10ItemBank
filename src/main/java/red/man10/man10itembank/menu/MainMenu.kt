package red.man10.man10itembank.menu

import org.bukkit.Material
import org.bukkit.entity.Player

class MainMenu(p:Player) : MenuFramework(p,36, "§f§lアイテムバンク") {

    override fun init() {
        val pane = Button(Material.LIGHT_BLUE_STAINED_GLASS_PANE)
        pane.title("")

        fill(pane)

        val putItemButton = Button(Material.CHEST)
        putItemButton.title("§a§lアイテムをアイテムバンクにしまう")

        putItemButton.setClickAction { e ->
            PutMenu(p).open()
        }

        arrayOf(10,11,12,19,20,21).forEach { setButton(putItemButton,it) }

        val takeItemButton = Button(Material.DISPENSER)
        takeItemButton.title("§a§lアイテムをアイテムバンクから取り出す")

        takeItemButton.setClickAction { e ->
            TakeMenu(p,0).open()
        }

        arrayOf(14,15,16,23,24,25).forEach { setButton(takeItemButton,it) }

    }

}