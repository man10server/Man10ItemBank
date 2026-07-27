package red.man10.mibtest

import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10itembank.ItemData
import red.man10.man10itembank.util.Utility
import java.io.File

/**
 * Man10ItemBank の在庫操作を実サーバー上で検証するための、e2e専用プラグイン。
 *
 * /mibop も /ipay もオンラインのプレイヤーを要求するためRCONからは叩けない。
 * このプラグインが ItemData を直接呼ぶことで、クライアント無しで在庫操作を通す。
 *
 * kotlin-stdlib を同梱しており、実サーバーの Man10CommonLibs と同じ役割も兼ねる。
 */
class MibTest : JavaPlugin(), CommandExecutor {

    lateinit var db: Db
        private set

    @Volatile
    private var running = false

    private val resultFile: File
        get() = File(server.worldContainer, "mibtest-result.txt")

    override fun onEnable() {

        saveDefaultConfig()

        val host = config.getString("mysql.host") ?: "mysql"
        val port = config.getInt("mysql.port", 3306)
        val database = config.getString("mysql.db") ?: "itembank"
        val user = config.getString("mysql.user") ?: "itembank"
        val pass = config.getString("mysql.pass") ?: "itembank"

        db = Db(
            "jdbc:mysql://$host:$port/$database?sslMode=DISABLED&allowPublicKeyRetrieval=true" +
                    "&characterEncoding=UTF-8&connectionTimeZone=SERVER&useAffectedRows=false",
            user,
            pass
        )

        getCommand("mibtest")!!.setExecutor(this)

        logger.info("MibTest 有効化 (db=$host:$port/$database)")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {

        when (args.firstOrNull()) {

            "seed" -> seed(sender)

            "run" -> run(sender)

            "status" -> sender.sendMessage(
                "itemQueue=${ItemData.getQueueSize()} " +
                        "logQueue=${red.man10.man10itembank.Log.queueSize()} " +
                        "indexSize=${ItemData.getItemIndexMap().size}"
            )

            else -> {
                sender.sendMessage("/mibtest seed   : 検証用アイテムを item_index へ登録する(要サーバー再起動)")
                sender.sendMessage("/mibtest run    : 在庫操作の検証を実行する")
                sender.sendMessage("/mibtest status : キューの状態を表示する")
            }
        }

        return true
    }

    ////////////////////////////////
    //      seed
    ////////////////////////////////

    /**
     * 検証用アイテムを item_index に直接INSERTする。
     *
     * ItemData.registerItem は Player を要求し、ItemIndex の再読込も private なので、
     * SQLで入れてからサーバーを再起動して読み込ませる。
     */
    private fun seed(sender: CommandSender) {

        val itemKey = config.getString("item.key") ?: "e2e_diamond"

        //ItemStackのシリアライズはメインスレッドで行う
        val base64 = runCatching { Utility.itemToBase64(ItemStack(Material.DIAMOND)) }.getOrElse {
            sender.sendMessage("シリアライズに失敗しました: ${it.message}")
            return
        }

        Thread({
            try {
                val exists = db.int("SELECT COUNT(*) FROM item_index WHERE item_key = ?", itemKey) > 0

                if (exists) {
                    logger.info("SEED already-present item_key=$itemKey")
                    return@Thread
                }

                db.update(
                    "INSERT INTO item_index (item_key, item_name, price, bid, ask, tick, time, disabled, base64) " +
                            "VALUES (?, ?, ?, ?, ?, ?, now(), 0, ?)",
                    itemKey, "Diamond", 100.0, 90.0, 110.0, 1.0, base64
                )

                logger.info("SEED inserted item_key=$itemKey")

            } catch (e: Throwable) {
                logger.severe("SEED failed: ${e.message}")
            }
        }, "MibTest-Seed").start()
    }

    ////////////////////////////////
    //      run
    ////////////////////////////////

    private fun run(sender: CommandSender) {

        if (running) {
            sender.sendMessage("既に実行中です")
            return
        }

        val itemKey = config.getString("item.key") ?: "e2e_diamond"

        running = true
        resultFile.delete()

        Thread({
            val scenarios = Scenarios(this, db)

            val summary = try {
                scenarios.runAll(itemKey)
            } catch (e: Throwable) {
                logger.severe("検証の実行に失敗しました: ${e.message}")
                "RESULT pass=0 fail=1 (${e.message})"
            } finally {
                running = false
            }

            val body = scenarios.report() + summary + "\n"

            runCatching { resultFile.writeText(body) }
                .onFailure { logger.severe("結果の書き出しに失敗: ${it.message}") }

            logger.info(summary)

        }, "MibTest-Run").start()

        sender.sendMessage("検証を開始しました。結果は mibtest-result.txt に出ます")
    }
}
