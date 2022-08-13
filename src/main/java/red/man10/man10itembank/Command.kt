package red.man10.man10itembank

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

object Command : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>?): Boolean {

        if (args.isNullOrEmpty()){



            return true
        }

        when(args[0]){




        }

        return false
    }
}