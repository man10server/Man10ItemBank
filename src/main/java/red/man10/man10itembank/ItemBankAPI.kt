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

    fun addItemAmount(order: UUID?, target: UUID, key: String, amount: Int, callBack : (Int?)->Unit = {}){
        ItemData.addItemAmount(order, target, ItemData.getID(key), amount, callBack)
    }

    fun takeItemAmount(order: UUID?, target:UUID, key: String, amount: Int, callBack: (Int?)->Unit =  {}){
        ItemData.takeItemAmount(order, target, ItemData.getID(key), amount, callBack)
    }

    fun getItemAmount(uuid:UUID,key: String,callBack: (Int?)->Unit = {}){
        ItemData.getItemAmount(uuid, ItemData.getID(key), callBack)
    }


}