package red.man10.man10itembank

import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10itembank.menu.ItemPayMenu
import red.man10.man10itembank.menu.MainMenu
import red.man10.man10itembank.util.Utility
import red.man10.man10itembank.util.Utility.prefix
import red.man10.man10itembank.util.Utility.sendError
import red.man10.man10itembank.util.Utility.sendMsg
import java.util.*

object Command : CommandExecutor {

    private val itemPayMap = HashMap<UUID,Pair<UUID,Int>>()
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {

        if (label == "ipay"){
            if (sender !is Player)return false
            if (!Utility.hasUserPermission(sender))return false

            //ipay sender amount
            if (args.size != 2){
                sendMsg(sender,"§d§l/ipay <送る相手> <個数> : アイテムバンクのアイテムを送ります")
                sendMsg(sender,"§d§lコマンドを打つとアイテムを選ぶメニューが開きます")
                return true
            }

            if (args[0] == "cancel"){
                itemPayMap.remove(sender.uniqueId)
                sendMsg(sender,"§d§アイテムペイをキャンセルしました")
                return true
            }

            if (args[0] == "c1"){

                val data = itemPayMap[sender.uniqueId]?:return true
                val p = Bukkit.getPlayer(data.first)?:return true
                val item = args[1]
                val itemData = ItemData.getItemData(item) ?: return true

                sendMsg(sender,"§a§l相手:${p.name} アイテム:${itemData.itemKey} 個数:${String.format("%,d", data.second)}個")
                sender.sendMessage(text(prefix).append(text("§b§l§n[送る]").clickEvent(ClickEvent.runCommand("/ipay c2 $item"))))
                return true
            }

            //確認処理
            if (args[0] == "c2"){

                val data = itemPayMap[sender.uniqueId]?:return true
                val id = ItemData.getID(args[1])

                if (id == -1){
                    return true
                }

                itemPayMap.remove(sender.uniqueId)

                ItemData.takeItemAmount(sender.uniqueId,sender.uniqueId,id,data.second){
                    if (it == null){
                        sendMsg(sender,"アイテムの数が足りません！")
                        return@takeItemAmount
                    }

                    ItemData.addItemAmount(sender.uniqueId,data.first,id,data.second){
                        val p = Bukkit.getPlayer(data.first)?:return@addItemAmount
                        sendMsg(p,"§e§l${sender.name}から${args[1]}を${String.format("%,d",data.second)}個受け取りました")
                        sendMsg(sender,"§a${args[1]}を${data.second}個送りました")
                    }
                }

                return true
            }

            val uuid = Bukkit.getPlayer(args[0])?.uniqueId
            val amount = args[1].toIntOrNull()

            if (uuid == null){
                sendMsg(sender,"§c§lオンラインのプレイヤーにのみ送れます")
                return true
            }

            if (amount == null){
                sendMsg(sender,"§c§l数字で入力してください")
                return true
            }

            sendMsg(sender,"§e§l送るアイテムを選択してください")

            itemPayMap[sender.uniqueId] = Pair(uuid,amount)
            ItemPayMenu(sender,0).open()
        }

        if (label=="mib"){
            if (sender !is Player)return false
            if (!Utility.hasUserPermission(sender))return false

            if (args.isNotEmpty()){
                if (args[0] == "collect"){
                    if (args.size == 1){
                        sendError(sender,"/mib collect <on/off>")
                        return false
                    }
                    if (args[1] == "on"){
                        Man10ItemBank.denyAutoCollectUsers.remove(sender.uniqueId)
                        sendMsg(sender,"自動回収を有効にしました")
                        return false
                    }
                    if (args[1] == "off"){
                        Man10ItemBank.denyAutoCollectUsers.add(sender.uniqueId)
                        sendMsg(sender,"自動回収を無効にしました")
                        return false
                    }
                }
                return false
            }


            MainMenu(sender).open()
        }

        if (label=="mibop"){ mibop(sender, args) }

        return false
    }


    private fun mibop(sender: CommandSender, args: Array<out String>?){

        if (sender is Player && !Utility.hasOPPermission(sender))return

        if (args.isNullOrEmpty()){

            sendMsg(sender,"§d§lMan10ItemBank")
            sendMsg(sender,"§d§l----------運営用コマンド----------")
            sendMsg(sender,"§d§l/mibop register <識別名> <初期価格> <最低取引単位>   : 新アイテムを登録")
            sendMsg(sender,"§d§l/mibop unregister <id>                          : アイテムを削除")
            sendMsg(sender,"§d§l/mibop list                                     : 登録アイテム一覧 ")
            sendMsg(sender,"§d§l/mibop item <id>                                : アイテムのコピーを取得 ")
            sendMsg(sender,"§d§l----------指定プレイヤーのアイテムバンクを操作----------")
            sendMsg(sender,"§d§l/mibop add <player> <id> <amount>")
            sendMsg(sender,"§d§l/mibop take <player> <id> <amount>")
            sendMsg(sender,"§d§l/mibop set <player> <id> <amount>")
            sendMsg(sender,"§d§l/mibop show <player> <id>")

            return
        }

        when(args[0]){

            "queue" ->{
                sendMsg(sender,ItemData.getQueueSize().toString())

            }

            "register" ->{
                if (args.size!=4){
                    sendError(sender,"/mibop register <識別名> <初期価格> <Tick>")
                    return
                }

                if (sender !is Player){
                    sendError(sender,"このコマンドは、プレイヤーでないと実行できません")
                    return
                }

                val key = args[1]
                val initialPrice = args[2].toDoubleOrNull()
                val tick = args[3].toDoubleOrNull()

                if (initialPrice == null || tick == null){
                    sendError(sender,"入力に誤りがあります")
                    return
                }

                val item = sender.inventory.itemInMainHand

                if (item.amount == 0 || item.type == Material.AIR){
                    sendError(sender,"メインハンドにアイテムを持ってください")
                    return
                }

                ItemData.registerItem(sender,key,item.asOne(),initialPrice, tick){
                    when(it){

                        ItemData.EnumResult.SUCCESSFUL ->{
                            sendMsg(sender,"登録成功！")
                        }
                        ItemData.EnumResult.FAILED->{
                            sendError(sender,"失敗！同一識別名のアイテムが既に登録されている可能性があります！")
                        }
                    }
                }

            }

            "unregister" ->{
                if (args.size != 2){
                    sendError(sender,"/mib unregister <id>")
                    return
                }

                val id = args[1].toIntOrNull()

                if (id == null){
                    sendError(sender,"入力に誤りがあります")
                    return
                }

                if (sender !is Player){
                    sendError(sender,"このコマンドは、プレイヤーでないと実行できません")
                    return
                }

                ItemData.unregisterItem(sender,id){
                    when(it){

                        ItemData.EnumResult.SUCCESSFUL ->{
                            sendMsg(sender,"削除成功")
                        }
                        ItemData.EnumResult.FAILED->{
                            sendError(sender,"削除失敗")
                        }

                    }
                }
            }

            "list" ->{

                val list = ItemData.getItemIndexMap()

                for (data in list.values){
                    sendMsg(sender,"§e§lID:${data.id} Key:${data.itemKey}")
                }
            }

            "item" ->{

                if (args.size != 2){
                    sendError(sender,"/mib item <id>")
                    return
                }

                val id = args[1].toIntOrNull()

                if (id == null){
                    sendError(sender,"入力に誤りがあります")
                    return
                }

                if (sender !is Player){
                    sendError(sender,"このコマンドは、プレイヤーでないと実行できません")
                    return
                }

                val data = ItemData.getItemData(id)

                if (data==null){
                    sendError(sender,"存在しないIDです")
                    return
                }

                sender.inventory.addItem(data.item!!.clone())

            }

            //mibop add <player> <id> <amount>
            "add" ->{
                if (args.size!=4){
                    sendError(sender,"/mibop add <player> <id> <amount>")
                    return
                }

                val order = if (sender !is Player)null else sender.uniqueId
                val p = Bukkit.getPlayer(args[1])
                val id = args[2].toIntOrNull()
                val amount = args[3].toIntOrNull()

                if (p==null){
                    sendError(sender,"プレイヤーがオフラインです")
                    return
                }

                if (id ==null || amount == null){
                    sendError(sender,"入力に誤りがあります")
                    return
                }

                ItemData.addItemAmount(order,p.uniqueId,id,amount) {

                    if (it == -1){
                        sendError(sender,"失敗！")
                        return@addItemAmount
                    }

                    sendMsg(sender,"成功！現在の在庫数:${it}")
                }
            }

            "take" ->{

                if (args.size!=4){
                    sendError(sender,"/mibop take <player> <id> <amount>")
                    return
                }

                val order = if (sender !is Player)null else sender.uniqueId
                val p = Bukkit.getPlayer(args[1])
                val id = args[2].toIntOrNull()
                val amount = args[3].toIntOrNull()

                if (p==null){
                    sendError(sender,"プレイヤーがオフラインです")
                    return
                }

                if (id ==null || amount == null){
                    sendError(sender,"入力に誤りがあります")
                    return
                }

                ItemData.takeItemAmount(order,p.uniqueId,id,amount) {

                    if (it == -1){
                        sendError(sender,"失敗！")
                        return@takeItemAmount
                    }

                    sendMsg(sender,"成功！現在の在庫数:${it}")
                }
            }

            "set" ->{

                if (args.size!=4){
                    sendError(sender,"/mibop set <player> <id> <amount>")
                    return
                }

                val order = if (sender !is Player)null else sender.uniqueId
                val p = Bukkit.getPlayer(args[1])
                val id = args[2].toIntOrNull()
                val amount = args[3].toIntOrNull()

                if (p==null){
                    sendError(sender,"プレイヤーがオフラインです")
                    return
                }

                if (id ==null || amount == null){
                    sendError(sender,"入力に誤りがあります")
                    return
                }

                ItemData.setItemAmount(order,p.uniqueId,id,amount) {

                    if (it == -1){
                        sendError(sender,"失敗！")
                        return@setItemAmount
                    }

                    sendMsg(sender,"成功！現在の在庫数:${it}")
                }


            }

            "show" ->{

                if (args.size!=4){
                    sendError(sender,"/mibop show <player> <id>")
                    return
                }

                val p = Bukkit.getPlayer(args[1])
                val id = args[2].toIntOrNull()

                if (p==null){
                    sendError(sender,"プレイヤーがオフラインです")
                    return
                }

                if (id ==null){
                    sendError(sender,"入力に誤りがあります")
                    return
                }

                ItemData.getItemAmount(p.uniqueId,id) {

                    if (it == -1){
                        sendError(sender,"失敗！")
                        return@getItemAmount
                    }

                    sendMsg(sender,"現在の在庫数:${it}")
                }


            }

        }

    }

}