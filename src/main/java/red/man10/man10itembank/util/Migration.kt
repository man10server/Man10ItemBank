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

    private const val SCHEMA_RESOURCE = "/db.sql"

    /** db.sql が定義するテーブル。欠けていれば db.sql から作り直す */
    private val REQUIRED_TABLES = listOf("item_index", "item_storage", "storage_log", "system_log")

    /**
     * @param autoMigrate falseの場合は確認だけ行い、変更が必要なら手動実行を促して失敗を返す
     * @return 起動を続行してよいか
     */
    fun run(db: Database, logger: Logger, autoMigrate: Boolean): Boolean {

        if (!ensureTables(db, logger, autoMigrate)) return false

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
    //      手順0: テーブルの作成
    ////////////////////////////////

    /**
     * db.sql が定義するテーブルのうち、存在しないものだけを作る。
     *
     * 既にあるテーブルには一切触れないため、新規DBでも
     * 一部だけ欠けたDB(storage_logだけ無い等)でも同じ処理で足りる。
     * DDLは暗黙コミットを伴うので、途中で失敗しても次回起動時に残りから作られる。
     */
    private fun ensureTables(db: Database, logger: Logger, autoMigrate: Boolean): Boolean {

        val existing = existingTables(db, logger) ?: return false

        val missing = REQUIRED_TABLES.filterNot { it in existing }

        if (missing.isEmpty()) return true

        if (!autoMigrate) {
            logger.severe("次のテーブルがありません: ${missing.joinToString(", ")}")
            logger.severe("mysql.autoMigrate が false のため自動作成しません。")
            logger.severe("jar内の db.sql を実行してテーブルを作成してください。")
            return false
        }

        logger.warning("次のテーブルがないため db.sql から作成します: ${missing.joinToString(", ")}")

        val statements = loadSchemaStatements(logger) ?: return false

        for ((table, sql) in statements) {

            //既存のテーブルに対する文は実行しない
            if (table !in missing) continue

            val result = db.update(sql)

            if (result is DbResult.Failure) {
                logger.severe("テーブルの作成に失敗しました ($table): ${result.message}")
                logger.severe("DBユーザーにCREATE権限があるか確認してください。")
                return false
            }
        }

        val after = existingTables(db, logger) ?: return false
        val stillMissing = REQUIRED_TABLES.filterNot { it in after }

        if (stillMissing.isNotEmpty()) {
            logger.severe("作成後もテーブルを確認できませんでした: ${stillMissing.joinToString(", ")}")
            return false
        }

        logger.warning("テーブルを作成しました: ${missing.joinToString(", ")}")

        return true
    }

    /**
     * jar内の db.sql を「対象テーブル名 → SQL」に分解する。
     *
     * db.sql に解釈できない文が増えた場合は黙って飛ばさず失敗させる。
     * (作成漏れに気づかないまま起動するほうが危険なため)
     */
    private fun loadSchemaStatements(logger: Logger): List<Pair<String, String>>? {

        val text = runCatching {
            Migration::class.java.getResourceAsStream(SCHEMA_RESOURCE)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        if (text.isNullOrBlank()) {
            logger.severe("jar内に $SCHEMA_RESOURCE が見つかりません。")
            return null
        }

        val createTable = Regex("^create\\s+table\\s+`?(\\w+)`?", RegexOption.IGNORE_CASE)
        val createIndex = Regex("^create\\s+(?:unique\\s+)?index\\s+`?\\w+`?\\s+on\\s+`?(\\w+)`?", RegexOption.IGNORE_CASE)

        val statements = mutableListOf<Pair<String, String>>()

        val body = text.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")

        for (raw in body.split(";")) {

            val sql = raw.trim()
            if (sql.isEmpty()) continue

            val normalized = sql.replace(Regex("\\s+"), " ")

            val table = createTable.find(normalized)?.groupValues?.get(1)
                ?: createIndex.find(normalized)?.groupValues?.get(1)

            if (table == null) {
                logger.severe("$SCHEMA_RESOURCE に解釈できない文があります: ${normalized.take(80)}")
                return null
            }

            statements.add(table.lowercase() to sql)
        }

        return statements
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

    /**
     * このDBに存在するテーブル名。nullは確認失敗。
     *
     * 「無い」と「確認できなかった」を区別する。混同すると、
     * 一時的なDB障害を「テーブルが無い」と誤認して作成処理へ進んでしまう。
     */
    private fun existingTables(db: Database, logger: Logger): Set<String>? =
        db.query(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
        ) { rs ->
            val names = mutableSetOf<String>()
            while (rs.next()) names.add(rs.getString(1).lowercase())
            names
        }.valueOrLog(logger, "テーブル一覧の確認")

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
