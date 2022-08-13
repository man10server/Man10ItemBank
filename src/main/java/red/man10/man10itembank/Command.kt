package red.man10.man10itembank

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10itembank.util.Utility.sendError
import red.man10.man10itembank.util.Utility.sendMsg

object Command : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>?): Boolean {

        if (label=="mib"){ mib(sender,args) }

        if (label=="mibop"){ mibop(sender, args) }

        return false
    }

    fun mib(sender: CommandSender, args: Array<out String>?){
        if (args.isNullOrEmpty()){

            return
        }

        when(args[0]){

        }
    }

    fun mibop(sender: CommandSender, args: Array<out String>?){
        if (args.isNullOrEmpty()){

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

                        0 ->{
                            sendMsg(sender,"登録成功！")
                        }
                        1->{
                            sendError(sender,"同一識別名のアイテムが既に存在します")
                        }

                        2->{
                            sendError(sender,"登録失敗")
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

                        0 ->{
                            sendMsg(sender,"削除成功！")
                        }
                        1->{
                            sendError(sender,"存在しないアイテムです")
                        }

                        2->{
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

            "get" ->{

                if (args.size != 2){
                    sendError(sender,"/mib get <id>")
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

                sender.inventory.addItem(data.item!!)

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

            "get" ->{

                if (args.size!=4){
                    sendError(sender,"/mibop get <player> <id>")
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