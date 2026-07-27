package red.man10.man10itembank

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10itembank.util.Database
import red.man10.man10itembank.util.DbResult
import red.man10.man10itembank.util.SqlStatement
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * 監査ログの書き込み。
 *
 * ログは失われても在庫は壊れないため、在庫処理とは別スレッドで非同期に書く。
 * ただし「失敗を無視する」ことはせず、書けなかった場合はサーバーログに全文を残す。
 */
object Log {

    private const val SHUTDOWN_TIMEOUT_MS = 10_000L

    private val queue = LinkedBlockingQueue<SqlStatement>()
    private val poison = SqlStatement("", emptyList())

    private lateinit var plugin: JavaPlugin
    private lateinit var db: Database

    private var worker: Thread? = null

    // varchar(16)の列に入れるため。超過するとstrictモードでINSERTが落ちてログが消える
    private const val NAME_LIMIT = 16

    fun start(plugin: JavaPlugin, database: Database) {

        this.plugin = plugin
        this.db = database

        worker = Thread({ loop() }, "Man10ItemBank-Log").also {
            it.isDaemon = false
            it.start()
        }
    }

    /**
     * 残っているログを書き切ってからスレッドを止める。
     */
    fun shutdown() {

        val thread = worker ?: return

        queue.add(poison)
        thread.join(SHUTDOWN_TIMEOUT_MS)

        if (thread.isAlive) {
            thread.interrupt()
        }

        val remaining = queue.count { it !== poison }
        if (remaining > 0) {
            plugin.logger.severe("ログ${remaining}件を書き込めませんでした")
            queue.forEach { if (it !== poison) plugin.logger.severe("未書き込みログ: ${format(it)}") }
        }

        worker = null
    }

    fun queueSize(): Int = queue.size

    //システムログ
    fun systemLog(uuid: UUID?, action: String) {

        val name = uuid?.let { Bukkit.getOfflinePlayer(it).name } ?: "Server"

        queue.add(
            SqlStatement(
                "INSERT INTO system_log (player, uuid, action, time) VALUES (?, ?, ?, now())",
                listOf(name.take(NAME_LIMIT), uuid?.toString(), action.take(128))
            )
        )
    }

    /**
     * mibをいじった時にログをとる orderがnullの時は、鯖がmibを編集した場合
     *
     * @param editAmount 変動量。増加は正、減少は負。在庫が動かない記録用の行は0
     * @param storageAmount 変動後の在庫。不明な場合(記録用の行など)はnullを渡す。
     *                      0を入れると在庫0だったことになり集計が壊れる
     */
    fun storageLog(order: UUID?, target: UUID, id: Int, editAmount: Int, storageAmount: Int?, action: String) {

        // 名前が解決できないUUIDでもログは必ず残す(旧実装は name!! でNPEになり、
        // DB更新後・現物受け渡し前に処理が中断してアイテムが消えていた)
        val targetName = Bukkit.getOfflinePlayer(target).name ?: target.toString()

        var orderName = "Server"
        var orderPlayer: Player? = null

        if (order != null) {
            val offlinePlayer = Bukkit.getOfflinePlayer(order)
            orderPlayer = offlinePlayer.player
            orderName = offlinePlayer.name ?: order.toString()
        }

        val location = orderPlayer?.location

        queue.add(
            SqlStatement(
                "INSERT INTO storage_log " +
                        "(item_id, item_key, order_player, order_uuid, target_player, target_uuid, " +
                        "action, edit_amount, storage_amount, world, x, y, z, time) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                listOf(
                    id,
                    ItemData.getItemData(id)?.itemKey,
                    orderName.take(NAME_LIMIT),
                    order?.toString(),
                    targetName.take(NAME_LIMIT),
                    target.toString(),
                    action.take(64),
                    editAmount,
                    storageAmount,
                    (location?.world?.name ?: "none").take(NAME_LIMIT),
                    location?.x ?: 0.0,
                    location?.y ?: 0.0,
                    location?.z ?: 0.0,
                )
            )
        )
    }

    private fun loop() {

        while (true) {

            val statement = try {
                queue.take()
            } catch (e: InterruptedException) {
                break
            }

            if (statement === poison) break

            when (val result = db.update(statement)) {
                is DbResult.Failure ->
                    // ログは再現できないので、消えた内容をサーバーログに残す
                    plugin.logger.severe("ログの書き込みに失敗しました: ${result.message} / ${format(statement)}")

                is DbResult.Success -> Unit
            }
        }
    }

    private fun format(statement: SqlStatement): String =
        "${statement.sql} params=${statement.params}"
}
