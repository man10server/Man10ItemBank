package red.man10.man10itembank.util

import java.util.logging.Logger

/**
 * 起動時のスキーマ移行。
 *
 * 全ての手順は「実行が必要か」を確認してから走るため、何度実行しても結果が変わらない(冪等)。
 * 途中で失敗しても、次回起動時に残りの手順から再開できる。
 *
 * item_storage(uuid, item_id) のUNIQUE制約は原子的な在庫操作の前提であり、
 * 無いまま動かすと重複行の片方だけが更新される事故が起きるため、
 * 移行できなかった場合はプラグインを起動させない。
 */
object Migration {

    private const val UNIQUE_INDEX_NAME = "item_storage_uuid_item_id_uindex"

    /**
     * @param autoMigrate falseの場合は確認だけ行い、移行が必要なら手動実行を促して失敗を返す
     * @return 起動を続行してよいか
     */
    fun run(db: Database, logger: Logger, autoMigrate: Boolean): Boolean {

        if (!tableExists(db, logger, "item_storage")) {
            logger.severe("item_storage テーブルが存在しません。")
            logger.severe("jar内の db.sql を実行してテーブルを作成してください。")
            return false
        }

        val hasUnique = hasUniqueStorageIndex(db, logger) ?: return false

        //既に移行済み
        if (hasUnique) {
            //過去の移行で残った冗長なインデックスがあれば掃除する
            if (autoMigrate) dropRedundantStorageIndex(db, logger)
            return true
        }

        if (!autoMigrate) {
            logger.severe("item_storage に UNIQUE(uuid, item_id) がありません。")
            logger.severe("mysql.autoMigrate が false のため自動移行しません。")
            logger.severe("jar内の db_migration_1.sql を手順通りに実行してください。")
            return false
        }

        logger.warning("===== item_storage のスキーマ移行を開始します =====")

        if (!mergeDuplicatedStorages(db, logger)) return false
        if (!addUniqueStorageIndex(db, logger)) return false

        dropRedundantStorageIndex(db, logger)

        //最終確認。ここがtrueにならない限り起動させない
        val verified = hasUniqueStorageIndex(db, logger) ?: return false

        if (!verified) {
            logger.severe("移行後もUNIQUE(uuid, item_id)を確認できませんでした")
            return false
        }

        logger.warning("===== スキーマ移行が完了しました =====")

        return true
    }

    ////////////////////////////////
    //      手順1: 重複行の統合
    ////////////////////////////////

    /**
     * 同じ(uuid, item_id)の行が複数ある場合、在庫を合算して最小idの1行へまとめる。
     *
     * 合算と削除は同一トランザクションで行う。
     * 分けて実行すると、合算だけが2回走った場合に在庫を二重計上してしまう。
     */
    private fun mergeDuplicatedStorages(db: Database, logger: Logger): Boolean {

        val duplicates = db.query(
            "SELECT uuid, item_id, COUNT(*) AS row_count, SUM(amount) AS total_amount, " +
                    "GROUP_CONCAT(id ORDER BY id) AS ids, " +
                    "GROUP_CONCAT(amount ORDER BY id) AS amounts " +
                    "FROM item_storage GROUP BY uuid, item_id HAVING row_count > 1"
        ) { rs ->
            val list = mutableListOf<String>()
            while (rs.next()) {
                list.add(
                    "uuid=${rs.getString("uuid")} item_id=${rs.getInt("item_id")} " +
                            "rows=${rs.getInt("row_count")} ids=[${rs.getString("ids")}] " +
                            "amounts=[${rs.getString("amounts")}] -> ${rs.getLong("total_amount")}"
                )
            }
            list
        }.valueOrLog(logger, "重複行の確認") ?: return false

        if (duplicates.isEmpty()) {
            logger.info("重複した倉庫行はありませんでした")
            return true
        }

        // 統合前の状態を必ず残す(手動で復元できるようにするため)
        logger.warning("重複した倉庫行を${duplicates.size}組検出しました。在庫を合算して1行へ統合します")
        duplicates.forEach { logger.warning("  $it") }

        val deleted = db.transaction("重複行の統合") { con ->

            //残す行(最小id)に合計値を入れる
            con.executeUpdate(
                "UPDATE item_storage AS s " +
                        "JOIN (SELECT MIN(id) AS keep_id, SUM(amount) AS total_amount " +
                        "      FROM item_storage GROUP BY uuid, item_id HAVING COUNT(*) > 1) AS d " +
                        "  ON s.id = d.keep_id " +
                        "SET s.amount = d.total_amount, s.time = now()"
            )

            //合算済みの余剰行を削除する
            con.executeUpdate(
                "DELETE s FROM item_storage AS s " +
                        "JOIN (SELECT MIN(id) AS keep_id, uuid, item_id " +
                        "      FROM item_storage GROUP BY uuid, item_id HAVING COUNT(*) > 1) AS d " +
                        "  ON s.uuid = d.uuid AND s.item_id = d.item_id " +
                        "WHERE s.id <> d.keep_id"
            )
        }.valueOrLog(logger, "重複行の統合") ?: return false

        logger.warning("重複行を統合しました(削除した行数: $deleted)")

        return true
    }

    ////////////////////////////////
    //      手順2: UNIQUE制約
    ////////////////////////////////

    private fun addUniqueStorageIndex(db: Database, logger: Logger): Boolean {

        //中断された移行で名前だけ残っている場合を検出する
        val nameTaken = db.query(
            "SELECT COUNT(*) FROM information_schema.statistics " +
                    "WHERE table_schema = DATABASE() AND table_name = 'item_storage' AND index_name = ?",
            UNIQUE_INDEX_NAME
        ) { rs -> rs.next() && rs.getInt(1) > 0 }
            .valueOrLog(logger, "インデックス名の確認") ?: return false

        if (nameTaken) {
            logger.severe("$UNIQUE_INDEX_NAME が期待した形で存在しません。手動で確認してください。")
            logger.severe("  SHOW INDEX FROM item_storage;")
            return false
        }

        logger.warning("UNIQUE(uuid, item_id) を作成します")

        // ALTER TABLE は暗黙コミットを伴うため、トランザクションに含めず単体で実行する
        val result = db.update(
            "ALTER TABLE item_storage ADD CONSTRAINT $UNIQUE_INDEX_NAME UNIQUE (uuid, item_id)"
        )

        if (result is DbResult.Failure) {
            logger.severe("UNIQUE制約の作成に失敗しました: ${result.message}")
            logger.severe("DBユーザーにALTER権限があるか確認してください。")
            return false
        }

        return true
    }

    /**
     * UNIQUE化により冗長になった非UNIQUEインデックスを削除する。
     * 失敗しても動作には影響しないため、警告のみで続行する。
     */
    private fun dropRedundantStorageIndex(db: Database, logger: Logger) {

        val names = db.query(
            "SELECT index_name FROM information_schema.statistics " +
                    "WHERE table_schema = DATABASE() AND table_name = 'item_storage' AND non_unique = 1 " +
                    "GROUP BY index_name " +
                    "HAVING COUNT(*) = 2 " +
                    "   AND SUM(column_name = 'uuid') = 1 " +
                    "   AND SUM(column_name = 'item_id') = 1"
        ) { rs ->
            val list = mutableListOf<String>()
            while (rs.next()) list.add(rs.getString("index_name"))
            list
        }.valueOrNull() ?: return

        for (name in names) {
            //識別子はパラメータ化できないが、値はinformation_schemaから取得したもの
            val result = db.update("ALTER TABLE item_storage DROP INDEX `$name`")

            if (result is DbResult.Failure) {
                logger.warning("冗長なインデックス $name の削除に失敗しました(動作に影響はありません)")
            } else {
                logger.info("冗長なインデックス $name を削除しました")
            }
        }
    }

    ////////////////////////////////
    //      確認
    ////////////////////////////////

    private fun tableExists(db: Database, logger: Logger, table: String): Boolean =
        db.query(
            "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name = ?", table
        ) { rs -> rs.next() && rs.getInt(1) > 0 }
            .valueOrLog(logger, "テーブルの確認") ?: false

    /** (uuid, item_id) だけで構成されたUNIQUEインデックスがあるか。nullは確認失敗 */
    private fun hasUniqueStorageIndex(db: Database, logger: Logger): Boolean? =
        db.query(
            "SELECT COUNT(*) FROM (" +
                    "  SELECT index_name FROM information_schema.statistics" +
                    "  WHERE table_schema = DATABASE() AND table_name = 'item_storage' AND non_unique = 0" +
                    "  GROUP BY index_name" +
                    "  HAVING COUNT(*) = 2" +
                    "     AND SUM(column_name = 'uuid') = 1" +
                    "     AND SUM(column_name = 'item_id') = 1" +
                    ") t"
        ) { rs -> if (rs.next()) rs.getInt(1) > 0 else false }
            .valueOrLog(logger, "UNIQUE制約の確認")

    private fun <T> DbResult<T>.valueOrLog(logger: Logger, what: String): T? = when (this) {
        is DbResult.Success -> value
        is DbResult.Failure -> {
            logger.severe("$what に失敗しました: $message")
            null
        }
    }
}
