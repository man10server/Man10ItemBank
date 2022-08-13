package red.man10.man10itembank

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import red.man10.man10itembank.ItemData.CallBack
import red.man10.man10itembank.ItemData.Transaction
import red.man10.man10itembank.util.MySQLManager
import red.man10.man10itembank.util.MySQLManager.Companion.escapeStringForMySQL
import red.man10.man10itembank.util.Utility
import red.man10.man10itembank.util.Utility.itemToBase64
import red.man10.man10itembank.util.Utility.log
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

object ItemData {

    private val transactionQueue  = LinkedBlockingQueue<Pair<Transaction,CallBack>>()
    private var itemIndex = ConcurrentHashMap<Int,ItemIndex>()

    private lateinit var mysql : MySQLManager

    fun getItemData(id:Int):ItemIndex?{
        return itemIndex[id]
    }

    fun getItemData(key:String):ItemIndex?{
        return itemIndex.filterValues { it.itemKey == key }[0]
    }

    //ItemIndexに新規アイテムを登録 0:成功、1:重複、2:失敗
    fun registerItem(player: Player, key:String, item:ItemStack,initialPrice:Double,tick:Double,callBack: CallBack = CallBack {}){

        val transaction = Transaction {

            if (getItemData(key)!=null){
                return@Transaction 1
            }

            mysql.execute("INSERT INTO item_index (item_key, item_name, price, bid, ask, tick, time, disabled, base64) " +
                    "VALUES ('${escapeStringForMySQL(key)}', '${escapeStringForMySQL(item.i18NDisplayName?:"")}', ${initialPrice}, ${initialPrice}, ${initialPrice}, ${tick}, DEFAULT, 0, '${itemToBase64(item)}');")

            asyncLoadItemIndex()

            if (getItemData(key)!=null){
                log("アイテムインデックス登録 item_key:${key}",player)
                return@Transaction 0
            }

            return@Transaction 2
        }

        addTransaction(transaction,callBack)
    }

    //アイテム削除 0:成功、1:存在しない、2:失敗
    fun unregisterItem(player: Player,id:Int,callBack: CallBack = CallBack {}){

        val transaction = Transaction{

            val data = getItemData(id)?:return@Transaction 1
            val key = data.itemKey

            mysql.execute("DELETE FROM item_index WHERE id=${id};")
            asyncLoadItemIndex()

            if (getItemData(id) == null){
                log("アイテムインデックス削除 item_key:${key}",player)
                return@Transaction 0
            }

            return@Transaction 2
        }

        addTransaction(transaction, callBack)

    }

    //返り値は在庫 -1は失敗or存在しないID
    fun addItemAmount(order:UUID?,target:UUID, id: Int, amount: Int, callBack: CallBack = CallBack {}){

        val transaction = Transaction {
            val nowAmount = asyncGetItemAmount(target, id)

            if (nowAmount == -1){
                return@Transaction -1
            }

            val newAmount = nowAmount+amount

            asyncSetItemAmount(target, id, newAmount)
            Log.storageLog(order,target,id,nowAmount,"AddItem")

            return@Transaction newAmount
        }

        addTransaction(transaction, callBack)

    }

    //返り値は在庫
    fun takeItemAmount(order: UUID?, target:UUID, id: Int, amount: Int, callBack: CallBack = CallBack {}){

        val transaction = Transaction {
            val nowAmount = asyncGetItemAmount(target, id)

            if (nowAmount == -1){
                return@Transaction -1
            }

            var newAmount = nowAmount-amount

            if (newAmount < 0){ newAmount = 0}
            asyncSetItemAmount(target, id, newAmount)
            Log.storageLog(order,target,id,nowAmount,"TakeItem")

            return@Transaction newAmount
        }

        addTransaction(transaction, callBack)

    }

    //アイテム数を設定する 返り値は最新の在庫
    fun setItemAmount(order: UUID?,target:UUID, id: Int, amount: Int, callBack: CallBack = CallBack {}){

        var newAmount = amount

        if (amount <0){ newAmount = 0 }

        val transaction = Transaction {

            //倉庫の有無をチェック
            val nowAmount = asyncGetItemAmount(target, id)

            if (nowAmount == -1){
                return@Transaction -1
            }

            asyncSetItemAmount(target, id, newAmount)
            Log.storageLog(order,target,id,amount,"SetItem")

            return@Transaction amount
        }

        addTransaction(transaction,callBack)

    }

    //アイテム数を取得する、callBack関数は、取得後に走る処理、引数のresultが取得した数
    fun getItemAmount(uuid:UUID,id: Int,callBack: CallBack = CallBack {}){
        val transaction = Transaction {
            return@Transaction asyncGetItemAmount(uuid, id)
        }
        addTransaction(transaction, callBack)
    }

/////////////////////////////////キューの中で、キューに突っ込む処理を入れないこと(キューが詰まるため)////////////////////////////////////////

    //同時に実行されてほしくない処理は、ここに処理を投げる
    private fun addTransaction(transaction:Transaction,callBack:CallBack= CallBack {}){
        transactionQueue.add(Pair(transaction,callBack))
    }

    //在庫を設定
    private fun asyncSetItemAmount(uuid: UUID,id: Int,amount:Int):Int{

        getItemData(id) ?: return -1

        mysql.execute("UPDATE item_storage SET amount = $amount WHERE uuid='${uuid}' and id=${id};")

        return 0
    }

    //在庫を取得
    private fun asyncGetItemAmount(uuid: UUID,id: Int):Int{

        getItemData(id) ?: return -1

        val rs = mysql.query("select amount from item_storage where uuid='${uuid}' and item_id=${id};")?:return -1

        if (!rs.next()){
            asyncCreateItemStorage(uuid,id)
        }

        val amount = rs.getInt("amount")

        rs.close()
        mysql.close()

        return amount
    }

    //アイテムバンクを作成 0:成功 2:失敗
    private fun asyncCreateItemStorage(uuid: UUID,id:Int):Int{

        val data = getItemData(id) ?: return 2

        val p = Bukkit.getOfflinePlayer(uuid)

        mysql.execute("INSERT INTO item_storage (player, uuid, item_id, item_key, amount, time) " +
                "VALUES ('${p.name}', '${uuid}', ${id}, '${data.itemKey}', DEFAULT, DEFAULT);")

        if (asyncGetItemAmount(uuid,id) != -1) {
            Log.storageLog(uuid,uuid,id,0,"CreateStorage")
            return 0
        }

        return 2
    }

    //ItemIndexの読み込み
    private fun asyncLoadItemIndex(){

        log("ItemIndexの読み込み")

        itemIndex.clear()

        val index = ConcurrentHashMap<Int,ItemIndex>()

        val rs = mysql.query("SELECT * FROM item_index where disabled=0;")?:return

        while (rs.next()){

            val data = ItemIndex()

            data.id = rs.getInt("id")
            data.itemKey = rs.getString("item_key")
            data.price = rs.getDouble("price")
            data.bid = rs.getDouble("bid")
            data.ask = rs.getDouble("ask")
            data.tick = rs.getDouble("tick")

            data.item = Utility.itemFromBase64(rs.getString("base64"))

            if (data.item == null){
                log("ID:${data.id},Name:${data.itemKey} アイテムデータの取得に失敗！")
                continue
            }

            index[data.id] = data
        }

        rs.close()
        mysql.close()

        itemIndex = index
    }


    fun interface Transaction{ fun onTransactionResult():Int}
    fun interface CallBack{ fun callBack(result: Int) }

    private fun runTransactionQueue(){

        log("トランザクションキュー起動")

        Thread{

            mysql = MySQLManager(Man10ItemBank.plugin,"Man10ItemBankTransaction")

            try {
                while (true){

                    val queue = transactionQueue.take()

                    val result = queue.first.onTransactionResult()

                    queue.second.callBack(result)

                }

            }catch (e:Exception){
                log("トランザクションキューのエラー:${e.message}")
            }
        }.start()

    }

    class ItemIndex{

        var id : Int = 0
        var itemKey : String = ""//識別名
        var price : Double = 0.0
        var bid : Double = 0.0
        var ask : Double = 0.0
        var tick : Double = 0.0
        var item : ItemStack? = null
    }

    init {
        runTransactionQueue()

        val transaction = Transaction{
            asyncLoadItemIndex()
            return@Transaction 0
        }

        addTransaction(transaction)

    }

}