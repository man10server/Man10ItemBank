package red.man10.man10itembank

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import red.man10.man10itembank.util.MySQLManager
import red.man10.man10itembank.util.MySQLManager.Companion.escapeStringForMySQL
import red.man10.man10itembank.util.Utility
import red.man10.man10itembank.util.Utility.itemToBase64
import red.man10.man10itembank.util.Utility.log
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

object ItemData {

    private val transactionQueue  = LinkedBlockingQueue<()->Unit>()
    private var itemIndex = ConcurrentHashMap<Int,ItemIndex>()

    private var transactionThread = Thread{ transaction() }

    private lateinit var mysql : MySQLManager

    init {
        runTransactionQueue()

        val transaction = Transaction@{
            asyncLoadItemIndex()
        }

        addTransaction(transaction)

    }
    //キューが詰まった時に確認するためのもの
    fun getQueueSize():Int{
        return transactionQueue.size
    }

    fun getItemData(id:Int):ItemIndex?{
        return itemIndex[id]
    }

    //ItemStackからItemIndex取得
    fun getItemData(item: ItemStack):ItemIndex?{

        return itemIndex.filter { it.value.item?.isSimilar(item)?:false }.getOrDefault(0, null)
    }

    //KeyからItemIndex取得
    fun getItemData(key: String): ItemIndex? {
        return itemIndex.filter { it.value.itemKey == key }.getOrDefault(0, null)
    }

    fun getID(key: String):Int{
        return getItemData(key)?.id?:-1
    }

    fun getItemIndexMap():Map<Int,ItemIndex>{
        return itemIndex
    }

    //ItemIndexに新規アイテムを登録
    fun registerItem(player: Player, key:String, item:ItemStack,initialPrice:Double,tick:Double,callBack: (EnumResult)->Unit = {}){

        val transaction = Transaction@{

            if (getItemData(key)!=null){
                callBack.invoke(EnumResult.FAILED)
                return@Transaction
            }

            mysql.execute("INSERT INTO item_index (item_key, item_name, price, bid, ask, tick, time, disabled, base64) " +
                    "VALUES ('${escapeStringForMySQL(key)}', '${escapeStringForMySQL(item.i18NDisplayName?:"")}', ${initialPrice}, ${initialPrice}, ${initialPrice}, ${tick}, DEFAULT, 0, '${itemToBase64(item)}');")

            asyncLoadItemIndex()

            if (getItemData(key)!=null){
                log("アイテムインデックス登録 item_key:${key}",player)
                callBack.invoke(EnumResult.SUCCESSFUL)
                return@Transaction
            }

            callBack.invoke(EnumResult.FAILED)
            return@Transaction
        }

        addTransaction(transaction)
    }

    //アイテム削除 0:成功、1:存在しない、2:失敗
    fun unregisterItem(player: Player,id:Int,callBack: (EnumResult)->Unit = {}){

        val transaction = Transaction@{

            val data = getItemData(id)

            if (data==null){
                callBack.invoke(EnumResult.FAILED)
                return@Transaction
            }

            val key = data.itemKey

            mysql.execute("DELETE FROM item_index WHERE id=${id};")
            asyncLoadItemIndex()

            if (getItemData(id) == null){
                log("アイテムインデックス削除 item_key:${key}",player)
                callBack.invoke(EnumResult.SUCCESSFUL)
                return@Transaction
            }

            callBack.invoke(EnumResult.FAILED)
            return@Transaction
        }

        addTransaction(transaction)

    }

    //返り値は在庫 nullは失敗
    fun addItemAmount(order:UUID?,target:UUID, id: Int, amount: Int, callBack : (Int?)->Unit = {}){

        val transaction = Transaction@ {
            val nowAmount = asyncGetItemAmount(target, id)

            if (nowAmount == null){
                callBack.invoke(null)
                return@Transaction
            }

            val newAmount = nowAmount+amount

            asyncSetItemAmount(target, id, newAmount)

            Log.storageLog(order,target,id,amount,newAmount,"AddItem")

            callBack.invoke(newAmount)
            return@Transaction
        }

        addTransaction(transaction)

    }

    //返り値は在庫(取り出せなかった場合は-1)
    fun takeItemAmount(order: UUID?, target:UUID, id: Int, amount: Int, callBack: (Int?)->Unit =  {}){

        val transaction = Transaction@ {
            val nowAmount = asyncGetItemAmount(target, id)

            if (nowAmount == null){
                callBack.invoke(null)
                return@Transaction
            }

            val newAmount = nowAmount-amount

            //取り出し失敗
            if (newAmount < 0){
                callBack.invoke(-1)
                return@Transaction
            }
            asyncSetItemAmount(target, id, newAmount)
            Log.storageLog(order,target,id,amount,newAmount,"TakeItem")

            callBack.invoke(newAmount)

            return@Transaction
        }

        addTransaction(transaction)

    }

    //アイテム数を設定する 返り値は最新の在庫
    fun setItemAmount(order: UUID?,target:UUID, id: Int, amount: Int, callBack: (Int?)->Unit = {}){

        var newAmount = amount

        if (amount <0){ newAmount = 0 }

        val transaction = Transaction@ {

            //倉庫の有無をチェック
            val nowAmount = asyncGetItemAmount(target, id)

            if (nowAmount == -1){
                callBack.invoke(null)
                return@Transaction
            }

            asyncSetItemAmount(target, id, newAmount)
            Log.storageLog(order,target,id,amount,newAmount,"SetItem")

            callBack.invoke(amount)
            return@Transaction
        }

        addTransaction(transaction)

    }

    //アイテム数を取得する、callBack関数は、取得後に走る処理、引数のresultが取得した数
    fun getItemAmount(uuid:UUID,id: Int,callBack: (Int)->Unit = {}){
        val transaction = Transaction@ {
            callBack.invoke(asyncGetItemAmount(uuid, id))
        }
        addTransaction(transaction)
    }

/////////////////////////////////キューの中で、キューに突っ込む処理を入れないこと(キューが詰まるため)////////////////////////////////////////

    //同時に実行されてほしくない処理は、ここに処理を投げる
    private fun addTransaction(transaction:()->Unit){
        transactionQueue.add(transaction)
    }

    //在庫を設定
    private fun asyncSetItemAmount(uuid: UUID,id: Int,amount:Int):Boolean{

        getItemData(id) ?: return false

        mysql.execute("UPDATE item_storage SET amount = $amount,time=now() WHERE uuid='${uuid}' and item_id=${id};")

        return true
    }

    //在庫を取得
    private fun asyncGetItemAmount(uuid: UUID,id: Int):Int{

        getItemData(id) ?: return -1

        val rs = mysql.query("select amount from item_storage where uuid='${uuid}' and item_id=${id};")?:return -1

        //倉庫が存在しなかった場合
        if (!rs.next()){
            asyncCreateItemStorage(uuid,id)
            return 0
        }

        val amount = rs.getInt("amount")

        rs.close()
        mysql.close()

        return amount
    }

    //アイテムバンクを作成 0:成功 2:失敗
    private fun asyncCreateItemStorage(uuid: UUID,id:Int):EnumResult{

        val data = getItemData(id) ?: return EnumResult.FAILED

        val p = Bukkit.getOfflinePlayer(uuid)

        mysql.execute("INSERT INTO item_storage (player, uuid, item_id, item_key, amount, time) " +
                "VALUES ('${p.name}', '${uuid}', ${id}, '${data.itemKey}', DEFAULT, DEFAULT);")

        if (asyncGetItemAmount(uuid,id) != -1) {
            Log.storageLog(null,uuid,id,0,0,"CreateStorage")
            return EnumResult.SUCCESSFUL
        }

        return EnumResult.FAILED
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


    private fun runTransactionQueue(){

        interruptTransactionQueue()

        transactionThread = Thread{ transaction() }
        transactionThread.start()

    }

    private fun interruptTransactionQueue(){

        if (transactionThread.isAlive)
            transactionThread.interrupt()

    }

    private fun transaction(){

        log("トランザクションキュー起動")

        mysql = MySQLManager(Man10ItemBank.plugin,"Man10ItemBankTransaction")

        while (true){

            try {
                val queue = transactionQueue.take()

                queue.invoke()

            }catch (e:InterruptedException){
              log("トランザクションの停止")
              break
            } catch (e:Exception){
                log("トランザクションキューのエラー:${e.stackTrace}")

            }
        }
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

    enum class EnumResult{
        SUCCESSFUL,
        FAILED,

    }



}