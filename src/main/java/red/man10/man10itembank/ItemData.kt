package red.man10.man10itembank

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10itembank.util.Database
import red.man10.man10itembank.util.DbResult
import red.man10.man10itembank.util.Utility
import red.man10.man10itembank.util.Utility.itemToBase64
import red.man10.man10itembank.util.Utility.log
import red.man10.man10itembank.util.executeQuery
import red.man10.man10itembank.util.executeUpdate
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.logging.Level

/**
 * 在庫操作の結果。
 *
 * 「在庫が足りない(＝正常な失敗)」と「DB障害(＝異常な失敗)」を区別する。
 * 旧実装はどちらもnull/-1で表現していたため、DB障害時に
 * 現物を渡してしまう(複製)・現物を消してしまう(消失)が起きていた。
 */
sealed interface StockResult {

    /** 成功。amountは更新後の在庫 */
    data class Updated(val amount: Int) : StockResult

    /** 在庫不足、または上限超過。DBは変更されていない */
    data object Insufficient : StockResult

    /** DB障害・未登録アイテムなど。DBが変更されたかは保証されない */
    data class Error(val message: String) : StockResult
}

object ItemData {

    private const val SHUTDOWN_TIMEOUT_MS = 15_000L
    private const val ITEM_NAME_LIMIT = 128
    private const val PLAYER_NAME_LIMIT = 16

    // amount列はint。DB側でオーバーフローさせず、WHERE句で弾く
    private const val MAX_AMOUNT = Int.MAX_VALUE

    private val transactionQueue = LinkedBlockingQueue<() -> Unit>()

    // 停止指示用の番兵。identityで判定するのでインスタンスを固定しておく
    private val poison: () -> Unit = {}

    private var itemIndex = ConcurrentHashMap<Int, ItemIndex>()

    private var cacheItemBank = ConcurrentHashMap<Pair<UUID, Int>, Int>()

    private lateinit var plugin: JavaPlugin
    private lateinit var db: Database

    private var transactionThread: Thread? = null

    /**
     * 起動処理。ItemIndexの読み込みに失敗した場合はfalseを返す。
     * (インデックスが空のまま動くと各所の data.item!! がNPEになるため、起動を止める)
     *
     * スキーマの移行はこれより前に Migration.run で完了している前提。
     */
    fun start(plugin: JavaPlugin, database: Database): Boolean {

        this.plugin = plugin
        this.db = database

        if (!loadItemIndex()) return false

        transactionThread = Thread({ transaction() }, "Man10ItemBank-Transaction").also {
            it.isDaemon = false
            it.start()
        }

        return true
    }

    /**
     * キューを処理し切ってからスレッドを止める。
     * 旧実装はonDisableが空で、停止時に未処理のアイテム操作がそのまま消えていた。
     */
    fun shutdown() {

        val thread = transactionThread ?: return

        transactionQueue.add(poison)
        thread.join(SHUTDOWN_TIMEOUT_MS)

        if (thread.isAlive) {
            plugin.logger.severe("在庫処理が${SHUTDOWN_TIMEOUT_MS}ms以内に完了しませんでした。強制停止します")
            thread.interrupt()
        }

        val remaining = transactionQueue.count { it !== poison }
        if (remaining > 0) {
            plugin.logger.severe("未処理の在庫操作が${remaining}件残りました")
        }

        transactionThread = null
    }

    //キューが詰まった時に確認するためのもの
    fun getQueueSize(): Int {
        return transactionQueue.size
    }

    fun getItemData(id: Int): ItemIndex? {
        return itemIndex[id]
    }

    //ItemStackからItemIndex取得
    fun getItemData(item: ItemStack): ItemIndex? {
        return itemIndex.values.firstOrNull { it.item?.isSimilar(item) ?: false }
    }

    //KeyからItemIndex取得
    fun getItemData(key: String): ItemIndex? {
        return itemIndex.values.firstOrNull { it.itemKey == key }
    }

    fun getID(key: String): Int {
        return getItemData(key)?.id ?: -1
    }

    fun getItemIndexMap(): Map<Int, ItemIndex> {
        return itemIndex
    }

    //価格更新
    fun setItemPrice(item: String, bid: Double, ask: Double) {

        val data = getItemData(item) ?: return
        // 旧実装は bid+ask/2 で括弧が無く、中間値になっていなかった
        data.price = (bid + ask) / 2
        data.bid = bid
        data.ask = ask

        itemIndex[data.id] = data

        addTransaction {
            asyncSaveItemIndex(data)
        }
    }

    //ItemIndexに新規アイテムを登録
    fun registerItem(
        player: Player,
        key: String,
        item: ItemStack,
        initialPrice: Double,
        tick: Double,
        callBack: (EnumResult) -> Unit = {}
    ) {

        addTransaction {

            if (getItemData(key) != null) {
                callBack.invoke(EnumResult.FAILED)
                return@addTransaction
            }

            val base64 = runCatching { itemToBase64(item) }.getOrNull()

            if (base64 == null) {
                plugin.logger.severe("アイテムのシリアライズに失敗しました item_key:$key")
                callBack.invoke(EnumResult.FAILED)
                return@addTransaction
            }

            // item_keyにUNIQUE制約が無いため、メモリキャッシュだけでなくDBも見る
            val duplicated = db.query(
                "SELECT COUNT(*) FROM item_index WHERE item_key = ? AND disabled = 0", key
            ) { rs -> if (rs.next()) rs.getInt(1) else 0 }

            if (duplicated !is DbResult.Success || duplicated.value > 0) {
                callBack.invoke(EnumResult.FAILED)
                return@addTransaction
            }

            val result = db.update(
                "INSERT INTO item_index (item_key, item_name, price, bid, ask, tick, time, disabled, base64) " +
                        "VALUES (?, ?, ?, ?, ?, ?, now(), 0, ?)",
                key,
                (item.i18NDisplayName ?: "").take(ITEM_NAME_LIMIT),
                initialPrice,
                initialPrice,
                initialPrice,
                tick,
                base64
            )

            if (result is DbResult.Failure) {
                callBack.invoke(EnumResult.FAILED)
                return@addTransaction
            }

            loadItemIndex()

            if (getItemData(key) != null) {
                log("アイテムインデックス登録 item_key:${key}", player)
                callBack.invoke(EnumResult.SUCCESSFUL)
                return@addTransaction
            }

            callBack.invoke(EnumResult.FAILED)
        }
    }

    //アイテム削除
    fun unregisterItem(player: Player, id: Int, callBack: (EnumResult) -> Unit = {}) {

        addTransaction {

            val data = getItemData(id)

            if (data == null) {
                callBack.invoke(EnumResult.FAILED)
                return@addTransaction
            }

            val key = data.itemKey

            val result = db.update("DELETE FROM item_index WHERE id = ?", id)

            if (result is DbResult.Failure) {
                callBack.invoke(EnumResult.FAILED)
                return@addTransaction
            }

            loadItemIndex()

            if (getItemData(id) == null) {
                log("アイテムインデックス削除 item_key:${key}", player)
                callBack.invoke(EnumResult.SUCCESSFUL)
                return@addTransaction
            }

            callBack.invoke(EnumResult.FAILED)
        }
    }

    ////////////////////////////////////////////////////////////
    //      在庫操作
    ////////////////////////////////////////////////////////////

    /**
     * 在庫を増減する。失敗理由まで受け取れるので、プラグイン内からはこれを使う。
     *
     * @param delta 正で追加、負で取り出し
     */
    fun changeItemAmount(
        order: UUID?,
        target: UUID,
        id: Int,
        delta: Int,
        action: String,
        callBack: (StockResult) -> Unit = {}
    ) {

        addTransaction {

            val result = asyncChangeAmount(target, id, delta)

            when (result) {
                is StockResult.Updated -> Log.storageLog(order, target, id, delta, result.amount, action)

                is StockResult.Error -> plugin.logger.severe(
                    "在庫操作に失敗しました($action) target=$target item_id=$id delta=$delta : ${result.message}"
                )

                is StockResult.Insufficient -> Unit
            }

            callBack.invoke(result)
        }
    }

    /**
     * アイテムを別のプレイヤーへ移す。
     * 減算と加算を1つのDBトランザクションで行うため、片側だけ成功することがない。
     */
    fun transferItem(
        order: UUID?,
        from: UUID,
        to: UUID,
        id: Int,
        amount: Int,
        callBack: (StockResult) -> Unit = {}
    ) {

        addTransaction {

            val result = asyncTransfer(order, from, to, id, amount)

            if (result is StockResult.Error) {
                plugin.logger.severe(
                    "アイテムの送付に失敗しました from=$from to=$to item_id=$id amount=$amount : ${result.message}"
                )
            }

            callBack.invoke(result)
        }
    }

    //返り値は在庫。失敗時はコールバックが呼ばれない(詳細が必要な場合はchangeItemAmountを使う)
    fun addItemAmount(order: UUID?, target: UUID, id: Int, amount: Int, callBack: (Int) -> Unit = {}) {

        if (amount < 0) {
            plugin.logger.severe("addItemAmountに負の数が渡されました target=$target item_id=$id amount=$amount")
            return
        }

        changeItemAmount(order, target, id, amount, "AddItem") {
            if (it is StockResult.Updated) callBack.invoke(it.amount)
        }
    }

    //返り値は在庫(取り出せなかった場合はnull)
    fun takeItemAmount(order: UUID?, target: UUID, id: Int, amount: Int, callBack: (Int?) -> Unit = {}) {

        if (amount < 0) {
            plugin.logger.severe("takeItemAmountに負の数が渡されました target=$target item_id=$id amount=$amount")
            callBack.invoke(null)
            return
        }

        changeItemAmount(order, target, id, -amount, "TakeItem") {
            callBack.invoke((it as? StockResult.Updated)?.amount)
        }
    }

    //アイテム数を設定する 返り値は最新の在庫
    fun setItemAmount(order: UUID?, target: UUID, id: Int, amount: Int, callBack: (Int?) -> Unit = {}) {

        val newAmount = if (amount < 0) 0 else amount

        addTransaction {

            // ログはasyncSetAmountの中でコミット成功後に書く(変動量を出すため前の値が必要)
            val result = asyncSetAmount(order, target, id, newAmount)

            if (result is StockResult.Error) {
                plugin.logger.severe("在庫の設定に失敗しました target=$target item_id=$id : ${result.message}")
            }

            callBack.invoke((result as? StockResult.Updated)?.amount)
        }
    }

    //アイテム数を取得する、callBack関数は、取得後に走る処理、引数のresultが取得した数
    fun getItemAmount(uuid: UUID, id: Int, callBack: (Int) -> Unit = {}) {
        addTransaction {
            callBack.invoke((asyncGetAmount(uuid, id) as? StockResult.Updated)?.amount ?: -1)
        }
    }

    fun addCacheItemAmount(uuid: UUID, id: Int, amount: Int) {
        cacheItemBank.merge(Pair(uuid, id), amount) { old, new -> old + new }
    }

    fun commitCachedItemBank(uuid: UUID) {

        val keys = cacheItemBank.keys.filter { it.first == uuid }

        for (key in keys) {

            // 先に取り出してからDBへ投げる
            val amount = cacheItemBank.remove(key) ?: continue

            // 旧実装はDBへ投げた直後にキャッシュを消していたため、書き込み失敗＝アイテム消失だった
            changeItemAmount(uuid, uuid, key.second, amount, "AutoCollect") { result ->
                if (result !is StockResult.Updated) {
                    addCacheItemAmount(uuid, key.second, amount)
                    plugin.logger.severe(
                        "自動回収の書き込みに失敗したためキャッシュへ戻しました " +
                                "uuid=$uuid item_id=${key.second} amount=$amount"
                    )
                }
            }
        }
    }

    /**
     * 全プレイヤーの自動回収キャッシュをDBへ流す。
     * サーバー停止時、PlayerQuitEventはプラグイン無効化後に発火することがあるため
     * onDisableから明示的に呼ぶ。
     */
    fun flushAllCaches() {

        val uuids = cacheItemBank.keys.map { it.first }.distinct()

        if (uuids.isEmpty()) return

        plugin.logger.info("自動回収キャッシュ${cacheItemBank.size}件を書き込みます")

        uuids.forEach { commitCachedItemBank(it) }
    }

/////////////////////////////////キューの中で、キューに突っ込む処理を入れないこと(キューが詰まるため)////////////////////////////////////////

    //同時に実行されてほしくない処理は、ここに処理を投げる
    private fun addTransaction(transaction: () -> Unit) {
        transactionQueue.add(transaction)
    }

    /**
     * 倉庫行が無ければ作る。UNIQUE(uuid,item_id)前提の冪等なupsert。
     * 旧実装はINSERT後にSELECTで確認し、失敗すると相互再帰でコネクションを食い潰していた。
     */
    private fun ensureStorageRow(con: Connection, uuid: UUID, id: Int, itemKey: String) {

        // オフラインプレイヤーの名前解決はMojang APIへの問い合わせでブロックしうるため、
        // オンラインのプレイヤーを優先して引く
        val name = Bukkit.getPlayer(uuid)?.name
            ?: Bukkit.getOfflinePlayer(uuid).name
            ?: uuid.toString()

        con.executeUpdate(
            "INSERT INTO item_storage (player, uuid, item_id, item_key, amount, time) " +
                    "VALUES (?, ?, ?, ?, 0, now()) ON DUPLICATE KEY UPDATE id = id",
            name.take(PLAYER_NAME_LIMIT), uuid, id, itemKey
        )
    }

    /**
     * 在庫を原子的に増減する。
     *
     * 旧実装は SELECT → 計算 → UPDATE(絶対値) だったため、
     * SELECTが失敗すると-1を基準に計算して実在庫を破壊していた。
     * 現在は1文の相対更新にし、在庫不足と上限超過をWHERE句で弾いている。
     */
    private fun asyncChangeAmount(uuid: UUID, id: Int, delta: Int): StockResult {

        val data = getItemData(id) ?: return StockResult.Error("未登録のアイテムです item_id=$id")

        val result = db.transaction("在庫更新 uuid=$uuid item_id=$id delta=$delta") { con ->

            ensureStorageRow(con, uuid, id, data.itemKey)

            val updated = con.executeUpdate(
                "UPDATE item_storage SET amount = amount + ?, time = now() " +
                        "WHERE uuid = ? AND item_id = ? AND amount + ? BETWEEN 0 AND ?",
                delta, uuid, id, delta, MAX_AMOUNT
            )

            //在庫不足または上限超過。DBは変更されていない
            if (updated == 0) return@transaction StockResult.Insufficient

            val amount = con.executeQuery(
                "SELECT amount FROM item_storage WHERE uuid = ? AND item_id = ?", uuid, id
            ) { rs -> if (rs.next()) rs.getInt(1) else null }

            if (amount == null) {
                error("更新後の在庫が取得できませんでした uuid=$uuid item_id=$id")
            }

            StockResult.Updated(amount)
        }

        return when (result) {
            is DbResult.Failure -> StockResult.Error(result.message)
            is DbResult.Success -> result.value
        }
    }

    /**
     * 在庫を絶対値で設定する。
     *
     * storage_log.edit_amount には「変動量」を残す。
     * 旧実装は設定後の絶対値を入れていたため、SUM(edit_amount)が在庫と一致しなかった。
     */
    private fun asyncSetAmount(order: UUID?, uuid: UUID, id: Int, amount: Int): StockResult {

        val data = getItemData(id) ?: return StockResult.Error("未登録のアイテムです item_id=$id")

        //ログを書くのに必要な変動量。コミット成功後に使う
        var delta = 0

        val result = db.transaction("在庫設定 uuid=$uuid item_id=$id amount=$amount") { con ->

            ensureStorageRow(con, uuid, id, data.itemKey)

            // upsertが対象行の排他ロックを取っているため、この読み取りと更新の間に割り込まれない
            val previous = con.executeQuery(
                "SELECT amount FROM item_storage WHERE uuid = ? AND item_id = ?", uuid, id
            ) { rs -> if (rs.next()) rs.getInt(1) else null }
                ?: error("設定対象の倉庫が見つかりません uuid=$uuid item_id=$id")

            val updated = con.executeUpdate(
                "UPDATE item_storage SET amount = ?, time = now() WHERE uuid = ? AND item_id = ?",
                amount, uuid, id
            )

            if (updated == 0) {
                error("在庫の更新対象が存在しませんでした uuid=$uuid item_id=$id")
            }

            delta = amount - previous

            StockResult.Updated(amount)
        }

        val outcome = when (result) {
            is DbResult.Failure -> StockResult.Error(result.message)
            is DbResult.Success -> result.value
        }

        //ロールバックされた操作をログに残さないよう、コミット成功後にだけ書く
        if (outcome is StockResult.Updated) {
            Log.storageLog(order, uuid, id, delta, outcome.amount, "SetItem")
        }

        return outcome
    }

    /** 在庫を取得する。行が無い場合は0(旧実装のように読むだけで行を作らない) */
    private fun asyncGetAmount(uuid: UUID, id: Int): StockResult {

        getItemData(id) ?: return StockResult.Error("未登録のアイテムです item_id=$id")

        val result = db.query(
            "SELECT amount FROM item_storage WHERE uuid = ? AND item_id = ?", uuid, id
        ) { rs -> if (rs.next()) rs.getInt(1) else 0 }

        return when (result) {
            is DbResult.Failure -> StockResult.Error(result.message)
            is DbResult.Success -> StockResult.Updated(result.value)
        }
    }

    /** 減算と加算を同一トランザクションで行う */
    private fun asyncTransfer(order: UUID?, from: UUID, to: UUID, id: Int, amount: Int): StockResult {

        val data = getItemData(id) ?: return StockResult.Error("未登録のアイテムです item_id=$id")

        if (amount <= 0) return StockResult.Error("送付する個数が不正です amount=$amount")
        if (from == to) return StockResult.Error("送付元と送付先が同一です uuid=$from")

        // 受け取り側の更新後の在庫。ログはコミット成功後に出すため、ここで受け取っておく
        var receiverAmount = 0

        val result = db.transaction("アイテム送付 from=$from to=$to item_id=$id amount=$amount") { con ->

            // 同時に逆方向の送付が走った場合のデッドロックを避けるため、
            // UUIDの順序でロックを取得する(upsertが対象行の排他ロックを取る)
            listOf(from, to).sortedBy { it.toString() }
                .forEach { ensureStorageRow(con, it, id, data.itemKey) }

            // 受け取り側が上限を超える場合、減算前に中止する
            val toAmount = con.executeQuery(
                "SELECT amount FROM item_storage WHERE uuid = ? AND item_id = ?", to, id
            ) { rs -> if (rs.next()) rs.getInt(1) else null }
                ?: error("受け取り側の倉庫が見つかりません uuid=$to")

            if (toAmount.toLong() + amount > MAX_AMOUNT) {
                return@transaction StockResult.Insufficient
            }

            val taken = con.executeUpdate(
                "UPDATE item_storage SET amount = amount - ?, time = now() " +
                        "WHERE uuid = ? AND item_id = ? AND amount >= ?",
                amount, from, id, amount
            )

            //在庫不足。この時点でまだ何も変更していない
            if (taken == 0) return@transaction StockResult.Insufficient

            val given = con.executeUpdate(
                "UPDATE item_storage SET amount = amount + ?, time = now() " +
                        "WHERE uuid = ? AND item_id = ? AND amount + ? <= ?",
                amount, to, id, amount, MAX_AMOUNT
            )

            //ここで失敗したら例外を投げてロールバックさせる(片側だけ成立させない)
            if (given == 0) {
                error("受け取り側の在庫を更新できませんでした uuid=$to")
            }

            val rest = con.executeQuery(
                "SELECT amount FROM item_storage WHERE uuid = ? AND item_id = ?", from, id
            ) { rs -> if (rs.next()) rs.getInt(1) else null }
                ?: error("送付元の在庫が取得できませんでした uuid=$from")

            receiverAmount = toAmount + amount

            StockResult.Updated(rest)
        }

        val outcome = when (result) {
            is DbResult.Failure -> StockResult.Error(result.message)
            is DbResult.Success -> result.value
        }

        // ロールバックされた送付をログに残さないよう、コミット成功後にだけ書く
        if (outcome is StockResult.Updated) {
            Log.storageLog(order, from, id, -amount, outcome.amount, "ItemPaySend")
            Log.storageLog(order, to, id, amount, receiverAmount, "ItemPayReceive")
        }

        return outcome
    }

    ////////////////////////////////////////////////////////////
    //      ItemIndex
    ////////////////////////////////////////////////////////////

    //ItemIndexの読み込み
    private fun loadItemIndex(): Boolean {

        log("ItemIndexの読み込み")

        val result = db.query(
            "SELECT id, item_key, price, bid, ask, tick, base64 FROM item_index WHERE disabled = 0"
        ) { rs ->

            val index = ConcurrentHashMap<Int, ItemIndex>()

            while (rs.next()) {

                val data = ItemIndex()

                data.id = rs.getInt("id")
                data.itemKey = rs.getString("item_key") ?: ""
                data.price = rs.getDouble("price")
                data.bid = rs.getDouble("bid")
                data.ask = rs.getDouble("ask")
                data.tick = rs.getDouble("tick")

                //1件の破損データで全体の読み込みを失敗させない
                data.item = runCatching { Utility.itemFromBase64(rs.getString("base64")) }.getOrNull()

                if (data.item == null) {
                    log("ID:${data.id},Name:${data.itemKey} アイテムデータの取得に失敗！")
                    continue
                }

                index[data.id] = data
            }

            index
        }

        return when (result) {

            is DbResult.Failure -> {
                // 旧実装は読み込み前にitemIndex.clear()していたため、失敗するとインデックスが空になり
                // メニューやログ生成の data.item!! / getItemData(id)!! がNPEになっていた
                plugin.logger.severe("ItemIndexの読み込みに失敗しました(既存のキャッシュを維持します): ${result.message}")
                false
            }

            is DbResult.Success -> {
                itemIndex = result.value
                log("ItemIndexを読み込みました (${result.value.size}件)")
                true
            }
        }
    }

    private fun asyncSaveItemIndex(itemIndex: ItemIndex) {

        val result = db.update(
            "UPDATE item_index SET price = ?, bid = ?, ask = ? WHERE id = ?",
            itemIndex.price, itemIndex.bid, itemIndex.ask, itemIndex.id
        )

        if (result is DbResult.Failure) {
            plugin.logger.severe("価格の保存に失敗しました item_id=${itemIndex.id}")
        }
    }

    private fun transaction() {

        log("トランザクションキュー起動")

        while (true) {

            val job = try {
                transactionQueue.take()
            } catch (e: InterruptedException) {
                break
            }

            if (job === poison) break

            try {
                job.invoke()
            } catch (e: Throwable) {
                // 旧実装は ${e.stackTrace} で配列のtoStringを出力しており原因が追えなかった。
                // またErrorを捕まえないためStackOverflowError等でスレッドごと死んでいた
                plugin.logger.log(Level.SEVERE, "在庫処理でエラーが発生しました", e)
            }
        }

        log("トランザクションキュー停止")
    }

    class ItemIndex {

        var id: Int = 0
        var itemKey: String = ""//識別名
        var price: Double = 0.0
        var bid: Double = 0.0
        var ask: Double = 0.0
        var tick: Double = 0.0

        // 呼び出し側が受け取ったItemStackを編集してもItemIndexの原本が変わらないようにする
        var item: ItemStack? = null
            get() = field?.clone()
    }

    enum class EnumResult {
        SUCCESSFUL,
        FAILED,
    }
}
