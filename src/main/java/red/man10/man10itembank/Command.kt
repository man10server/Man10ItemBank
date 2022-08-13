package red.man10.man10itembank

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10itembank.util.Utility
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