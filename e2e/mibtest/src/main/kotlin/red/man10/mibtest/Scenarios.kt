package red.man10.mibtest

import red.man10.man10itembank.ItemData
import red.man10.man10itembank.Log
import red.man10.man10itembank.StockResult
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 実サーバー・実MySQLに対して在庫操作を叩き、DBの中身で結果を照合する。
 *
 * ItemData のコールバックはトランザクションスレッドから呼ばれるため、
 * このクラスは必ずメインスレッド以外から実行すること。
 */
class Scenarios(private val plugin: MibTest, private val db: Db) {

    private companion object {
        const val OP_TIMEOUT_SEC = 30L
        const val MAX_AMOUNT = Int.MAX_VALUE
    }

    private val passed = mutableListOf<String>()
    private val failed = mutableListOf<String>()

    fun runAll(itemKey: String): String {

        val itemId = ItemData.getID(itemKey)

        if (itemId == -1) {
            return "RESULT pass=0 fail=1 (item_key=$itemKey が ItemIndex に読み込まれていません。seed後にサーバーを再起動してください)"
        }

        plugin.logger.info("item_key=$itemKey item_id=$itemId で検証を開始します")

        stockBasics(itemId)
        setAmountBehaviour(itemId)
        transferBehaviour(itemId)
        rejections(itemId)
        logConsistency(itemId)
        concurrency(itemId)
        autoCollectCache(itemId)

        return "RESULT pass=${passed.size} fail=${failed.size}"
    }

    fun report(): String = buildString {
        passed.forEach { appendLine("PASS  $it") }
        failed.forEach { appendLine("FAIL  $it") }
    }

    ////////////////////////////////////////////////////////////
    //      シナリオ
    ////////////////////////////////////////////////////////////

    /** 倉庫行の作成と相対更新 */
    private fun stockBasics(itemId: Int) {

        val uuid = UUID.randomUUID()

        scenario("upsert_creates_row_once") {

            expect("初回追加", StockResult.Updated(100), change(uuid, itemId, 100))
            expect("行数", 1, db.storageRowCount(uuid.toString(), itemId))

            expect("2回目の追加", StockResult.Updated(150), change(uuid, itemId, 50))
            //upsertが毎回INSERTしてしまうと重複行ができる(UNIQUE前提の確認)
            expect("行数は増えない", 1, db.storageRowCount(uuid.toString(), itemId))
            expect("DB上の在庫", 150, db.stock(uuid.toString(), itemId))
        }

        scenario("take_reduces_stock") {
            expect("取り出し", StockResult.Updated(120), change(uuid, itemId, -30))
            expect("DB上の在庫", 120, db.stock(uuid.toString(), itemId))
        }

        scenario("insufficient_leaves_db_untouched") {

            val before = db.stock(uuid.toString(), itemId)

            val result = change(uuid, itemId, -(before + 1))

            expect("在庫不足の判定", StockResult.Insufficient, result)
            expect("在庫は変化しない", before, db.stock(uuid.toString(), itemId))
        }

        scenario("overflow_is_rejected") {

            val overflowUuid = UUID.randomUUID()

            setAmount(overflowUuid, itemId, MAX_AMOUNT - 5)
            expect("準備", MAX_AMOUNT - 5, db.stock(overflowUuid.toString(), itemId))

            val result = change(overflowUuid, itemId, 10)

            expect("上限超過の判定", StockResult.Insufficient, result)
            expect("在庫は変化しない", MAX_AMOUNT - 5, db.stock(overflowUuid.toString(), itemId))
        }
    }

    /** 絶対値設定と、ログに残る変動量 */
    private fun setAmountBehaviour(itemId: Int) {

        val uuid = UUID.randomUUID()

        scenario("set_amount_records_delta_not_absolute") {

            change(uuid, itemId, 200)
            waitForQueues()

            expect("設定後の在庫", 500, setAmount(uuid, itemId, 500))
            waitForQueues()

            val last = db.lastLog(uuid.toString(), itemId)
                ?: throw AssertionError("storage_log に記録がありません")

            expect("action", "SetItem", last.first)
            //旧実装は設定後の絶対値(500)を入れていたため合計が在庫と合わなくなっていた
            expect("edit_amount は変動量", 300, last.second)
            expect("storage_amount は設定後の在庫", 500, last.third)
        }

        scenario("negative_set_clamps_to_zero") {

            val zeroUuid = UUID.randomUUID()

            change(zeroUuid, itemId, 10)
            expect("負数指定は0になる", 0, setAmount(zeroUuid, itemId, -50))
            expect("DB上の在庫", 0, db.stock(zeroUuid.toString(), itemId))
        }
    }

    /** 送付は片側だけ成立してはいけない */
    private fun transferBehaviour(itemId: Int) {

        scenario("transfer_moves_both_sides_atomically") {

            val from = UUID.randomUUID()
            val to = UUID.randomUUID()

            change(from, itemId, 100)
            change(to, itemId, 20)

            expect("送付元の残り", StockResult.Updated(60), transfer(from, to, itemId, 40))

            expect("送付元DB", 60, db.stock(from.toString(), itemId))
            expect("送付先DB", 60, db.stock(to.toString(), itemId))
        }

        scenario("transfer_insufficient_keeps_both_sides") {

            val from = UUID.randomUUID()
            val to = UUID.randomUUID()

            change(from, itemId, 10)
            change(to, itemId, 5)

            expect("在庫不足", StockResult.Insufficient, transfer(from, to, itemId, 999))

            expect("送付元DB", 10, db.stock(from.toString(), itemId))
            expect("送付先DB", 5, db.stock(to.toString(), itemId))
        }

        scenario("transfer_overflow_does_not_lose_sender_stock") {

            val from = UUID.randomUUID()
            val to = UUID.randomUUID()

            change(from, itemId, 100)
            setAmount(to, itemId, MAX_AMOUNT)

            //受け取り側が上限。減算だけ通ると送付元のアイテムが消える
            expect("上限超過で中止", StockResult.Insufficient, transfer(from, to, itemId, 50))

            expect("送付元DB(消えていないこと)", 100, db.stock(from.toString(), itemId))
            expect("送付先DB", MAX_AMOUNT, db.stock(to.toString(), itemId))
        }
    }

    /** 不正な入力 */
    private fun rejections(itemId: Int) {

        scenario("transfer_to_self_is_rejected") {

            val uuid = UUID.randomUUID()
            change(uuid, itemId, 100)

            val result = transfer(uuid, uuid, itemId, 10)

            if (result !is StockResult.Error) throw AssertionError("自分自身への送付が拒否されていません: $result")
            expect("在庫は変化しない", 100, db.stock(uuid.toString(), itemId))
        }

        scenario("unknown_item_is_rejected") {

            val result = change(UUID.randomUUID(), 999_999, 10)

            if (result !is StockResult.Error) throw AssertionError("未登録アイテムが拒否されていません: $result")
        }

        scenario("transfer_with_zero_amount_is_rejected") {

            val from = UUID.randomUUID()
            val to = UUID.randomUUID()
            change(from, itemId, 50)

            val result = transfer(from, to, itemId, 0)

            if (result !is StockResult.Error) throw AssertionError("0個の送付が拒否されていません: $result")
            expect("在庫は変化しない", 50, db.stock(from.toString(), itemId))
        }
    }

    /** storage_log の合計が実在庫と一致すること */
    private fun logConsistency(itemId: Int) {

        scenario("logged_deltas_sum_to_stock") {

            val uuid = UUID.randomUUID()

            change(uuid, itemId, 500)
            change(uuid, itemId, -120)
            change(uuid, itemId, 30)
            setAmount(uuid, itemId, 999)
            change(uuid, itemId, -99)

            waitForQueues()

            val stock = db.stock(uuid.toString(), itemId)
            val logged = db.loggedDelta(uuid.toString(), itemId)

            expect("実在庫", 900, stock)
            //edit_amount が符号付きで記録されていれば、合計は在庫と一致する
            expect("storage_log の変動量合計", stock.toLong(), logged)
        }
    }

    /** 複数サーバーが同じDBを触る状況の代替。相対更新が失われないこと */
    private fun concurrency(itemId: Int) {

        scenario("concurrent_relative_updates_are_not_lost") {

            val uuid = UUID.randomUUID()
            change(uuid, itemId, 0)

            val threads = 8
            val perThread = 25
            val pool = Executors.newFixedThreadPool(threads)
            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())

            try {
                val futures = (1..threads).map {
                    pool.submit {
                        runCatching {
                            db.connection().use { con ->
                                repeat(perThread) {
                                    db.update(
                                        con,
                                        "UPDATE item_storage SET amount = amount + ?, time = now() " +
                                                "WHERE uuid = ? AND item_id = ? AND amount + ? BETWEEN 0 AND ?",
                                        1, uuid.toString(), itemId, 1, MAX_AMOUNT
                                    )
                                }
                            }
                        }.onFailure { errors.add(it.toString()) }
                    }
                }
                futures.forEach { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            if (errors.isNotEmpty()) throw AssertionError("並列更新でエラー: ${errors.first()}")

            //read-modify-write ならロストアップデートでこの値を下回る
            expect("並列更新の反映数", threads * perThread, db.stock(uuid.toString(), itemId))
        }
    }

    /** 自動回収キャッシュ */
    private fun autoCollectCache(itemId: Int) {

        scenario("autocollect_cache_is_written_to_db") {

            val uuid = UUID.randomUUID()

            ItemData.addCacheItemAmount(uuid, itemId, 64)
            ItemData.addCacheItemAmount(uuid, itemId, 36)

            ItemData.commitCachedItemBank(uuid)
            waitForQueues()

            expect("キャッシュ分がDBへ入る", 100, db.stock(uuid.toString(), itemId))
        }
    }

    ////////////////////////////////////////////////////////////
    //      土台
    ////////////////////////////////////////////////////////////

    private fun scenario(name: String, block: () -> Unit) {
        try {
            block()
            passed.add(name)
            plugin.logger.info("PASS  $name")
        } catch (e: Throwable) {
            val message = e.message ?: e.toString()
            failed.add("$name : $message")
            plugin.logger.warning("FAIL  $name : $message")
        }
    }

    private fun expect(what: String, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("$what expected=$expected actual=$actual")
    }

    private fun change(target: UUID, itemId: Int, delta: Int): StockResult {

        val latch = CountDownLatch(1)
        var result: StockResult? = null

        ItemData.changeItemAmount(target, target, itemId, delta, "E2E") {
            result = it
            latch.countDown()
        }

        if (!latch.await(OP_TIMEOUT_SEC, TimeUnit.SECONDS)) throw AssertionError("在庫操作がタイムアウトしました")

        return result ?: throw AssertionError("結果が返りませんでした")
    }

    private fun transfer(from: UUID, to: UUID, itemId: Int, amount: Int): StockResult {

        val latch = CountDownLatch(1)
        var result: StockResult? = null

        ItemData.transferItem(from, from, to, itemId, amount) {
            result = it
            latch.countDown()
        }

        if (!latch.await(OP_TIMEOUT_SEC, TimeUnit.SECONDS)) throw AssertionError("送付がタイムアウトしました")

        return result ?: throw AssertionError("結果が返りませんでした")
    }

    private fun setAmount(target: UUID, itemId: Int, amount: Int): Int? {

        val latch = CountDownLatch(1)
        var result: Int? = null

        ItemData.setItemAmount(target, target, itemId, amount) {
            result = it
            latch.countDown()
        }

        if (!latch.await(OP_TIMEOUT_SEC, TimeUnit.SECONDS)) throw AssertionError("在庫設定がタイムアウトしました")

        return result
    }

    /** 在庫キューとログキューが空になるまで待つ(ログは非同期に書かれるため) */
    private fun waitForQueues(timeoutMs: Long = 15_000) {

        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            if (ItemData.getQueueSize() == 0 && Log.queueSize() == 0) {
                //取り出し済みで実行中のものがあるため少し待つ
                Thread.sleep(500)
                if (ItemData.getQueueSize() == 0 && Log.queueSize() == 0) return
            }
            Thread.sleep(100)
        }

        throw AssertionError("キューが${timeoutMs}ms以内に空になりませんでした")
    }
}
