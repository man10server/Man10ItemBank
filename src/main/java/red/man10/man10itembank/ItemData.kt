package red.man10.man10itembank

import org.bukkit.inventory.ItemStack
import red.man10.man10itembank.ItemData.CallBack
import red.man10.man10itembank.ItemData.Transaction
import red.man10.man10itembank.util.Utility
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

object ItemData {

    private val transactionQueue  = LinkedBlockingQueue<Pair<Transaction,CallBack>>()
    private var itemIndex = ConcurrentHashMap<Int,ItemStack>()

    fun getItemData(id:Int):ItemStack?{

        return itemIndex[id]
    }

    fun asyncRegisterItem(id:Int,name:String,item:ItemStack){


        asyncLoadItemIndex()
    }

    fun addItemAmount(uuid:UUID,id: Int,amount: Int,callBack: CallBack = CallBack {}){

        val transaction = Transaction {
            val nowAmount = asyncGetItemAmount(uuid, id)

            val newAmount = nowAmount+amount

            asyncSetItemAmount(uuid, id, newAmount)

            return@Transaction newAmount
        }

        addTransaction(transaction, callBack)

    }

    //返り値は残量
    fun takeItemAmount(uuid:UUID,id: Int,amount: Int,callBack: CallBack = CallBack {}){

        val transaction = Transaction {
            val nowAmount = asyncGetItemAmount(uuid, id)

            var newAmount = nowAmount-amount

            if (newAmount < 0){ newAmount = 0}

            asyncSetItemAmount(uuid, id, newAmount)

            return@Transaction newAmount
        }

        addTransaction(transaction, callBack)

    }

    //アイテム数を設定する
    fun setItemAmount(uuid:UUID,id: Int,amount: Int,callBack: CallBack = CallBack {}){

        var newAmount = amount

        if (amount <0){ newAmount = 0 }

        val transaction = Transaction {
            asyncSetItemAmount(uuid, id, newAmount)
            return@Transaction 0
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

/////////////////////////////////////////////////////////////////////////

    private fun addTransaction(transaction:Transaction,callBack:CallBack){
        transactionQueue.add(Pair(transaction,callBack))
    }

    private fun asyncSetItemAmount(uuid: UUID,id: Int,amount:Int){

    }

    private fun asyncGetItemAmount(uuid: UUID,id: Int):Int{

        return 0
    }

    private fun asyncLoadItemIndex():ConcurrentHashMap<Int,ItemStack>{

        Utility.log("item indexの読み込み")

        itemIndex.clear()

        val index = ConcurrentHashMap<Int,ItemStack>()

        itemIndex = index

        return index
    }


    fun interface Transaction{ fun onTransactionResult():Int}
    fun interface CallBack{ fun callBack(result: Int) }

    private fun runTransactionQueue(){

        Utility.log("トランザクションキュー起動")

        Thread{

            try {
                while (true){

                    val queue = transactionQueue.take()

                    val result = queue.first.onTransactionResult()

                    queue.second.callBack(result)

                }

            }catch (e:Exception){
                Utility.log("トランザクションのエラー:${e.message}")
            }
        }.start()

    }

    init {
        runTransactionQueue()
        asyncLoadItemIndex()
    }

}