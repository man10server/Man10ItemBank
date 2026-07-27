package red.man10.man10itembank

import java.util.*

object ItemBankAPI {

    fun getItemIndexList():List<String>{

        val list = mutableListOf<String>()

        for (index in ItemData.getItemIndexMap().values){
            list.add(index.itemKey)
        }
        return list
    }

    fun getItemData(item:String): ItemData.ItemIndex? {
        return ItemData.getItemData(item)
    }

    /**
     * アイテムを追加する。
     * callBackの引数は更新後の在庫で、nullは失敗(DB障害・上限超過・未登録アイテム)。
     */
    fun addItemAmount(order: UUID?, target: UUID, key: String, amount: Int, callBack : (Int?)->Unit = {}){

        if (amount < 0){
            callBack.invoke(null)
            return
        }

        ItemData.changeItemAmount(order, target, ItemData.getID(key), amount, "AddItem"){
            callBack.invoke((it as? StockResult.Updated)?.amount)
        }
    }

    /**
     * アイテムを取り出す。
     * callBackの引数は更新後の在庫で、nullは失敗(在庫不足・DB障害・未登録アイテム)。
     * nullが返った場合は現物を渡してはいけない。
     */
    fun takeItemAmount(order: UUID?, target:UUID, key: String, amount: Int, callBack: (Int?)->Unit =  {}){

        if (amount < 0){
            callBack.invoke(null)
            return
        }

        ItemData.changeItemAmount(order, target, ItemData.getID(key), -amount, "TakeItem"){
            callBack.invoke((it as? StockResult.Updated)?.amount)
        }
    }

    /**
     * アイテムを別のプレイヤーへ移す。減算と加算は同一トランザクションで行われる。
     * callBackの引数は送付元の残り在庫で、nullは失敗。
     */
    fun transferItemAmount(order: UUID?, from: UUID, to: UUID, key: String, amount: Int, callBack: (Int?)->Unit = {}){
        ItemData.transferItem(order, from, to, ItemData.getID(key), amount){
            callBack.invoke((it as? StockResult.Updated)?.amount)
        }
    }

    /** callBackの引数は在庫数。-1は失敗 */
    fun getItemAmount(uuid:UUID,key: String,callBack: (Int?)->Unit = {}){
        ItemData.getItemAmount(uuid, ItemData.getID(key), callBack)
    }

    fun setItemPrice(item: String,bid:Double,ask:Double){
        ItemData.setItemPrice(item, bid, ask)
    }

}
