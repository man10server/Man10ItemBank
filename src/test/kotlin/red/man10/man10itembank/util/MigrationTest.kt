package red.man10.man10itembank.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import red.man10.man10itembank.testsupport.MySqlSupport
import red.man10.man10itembank.testsupport.TestPluginContext

/**
 * 起動時スキーマ移行の検証。
 *
 * 本番の item_storage を不可逆に書き換える処理なので、
 * 「合算が正しいか」だけでなく「二重に走らせても在庫が増えないか」
 * 「途中で失敗した後に続きから再開できるか」まで確認する。
 */
class MigrationTest {

    private val opened = mutableListOf<Database>()

    private val uuidA = "11111111-1111-1111-1111-111111111111"
    private val uuidB = "22222222-2222-2222-2222-222222222222"

    @AfterEach
    fun tearDown() {
        opened.forEach { it.shutdown() }
        opened.clear()
    }

    ////////////////////////////////
    //      準備
    ////////////////////////////////

    private class Fixture(
        val schema: String,
        val db: Database,
        val context: TestPluginContext
    )

    private fun fixture(
        schemaSql: String?,
        user: String = MySqlSupport.APP_USER,
        password: String = MySqlSupport.APP_PASSWORD,
        autoMigrate: Boolean = true
    ): Fixture {

        val schema = MySqlSupport.createSchema("migtest")

        if (schemaSql != null) MySqlSupport.applySql(schema, schemaSql)

        val context = TestPluginContext(schema, user, password) {
            set("mysql.autoMigrate", autoMigrate)
        }

        val db = Database(context.plugin, "test").also { opened.add(it) }

        check(db.connect()) { "テスト用DBへ接続できませんでした" }

        return Fixture(schema, db, context)
    }

    private fun runMigration(f: Fixture, autoMigrate: Boolean = true): Boolean =
        Migration.run(f.db, f.context.logs.logger, autoMigrate)

    /** (uuid, item_id) だけで構成されたユニークインデックスがあるか */
    private fun hasUniqueIndex(schema: String): Boolean =
        MySqlSupport.queryOne(
            schema,
            "SELECT COUNT(*) FROM (" +
                    "  SELECT index_name FROM information_schema.statistics" +
                    "  WHERE table_schema = DATABASE() AND table_name = 'item_storage' AND non_unique = 0" +
                    "  GROUP BY index_name HAVING COUNT(*) = 2" +
                    "    AND SUM(column_name = 'uuid') = 1 AND SUM(column_name = 'item_id') = 1" +
                    ") t"
        ) { it.getInt(1) > 0 }

    private fun redundantIndexCount(schema: String): Int =
        MySqlSupport.queryOne(
            schema,
            "SELECT COUNT(*) FROM (" +
                    "  SELECT index_name FROM information_schema.statistics" +
                    "  WHERE table_schema = DATABASE() AND table_name = 'item_storage' AND non_unique = 1" +
                    "  GROUP BY index_name HAVING COUNT(*) = 2" +
                    "    AND SUM(column_name = 'uuid') = 1 AND SUM(column_name = 'item_id') = 1" +
                    ") t"
        ) { it.getInt(1) }

    private fun amountOf(schema: String, uuid: String, itemId: Int): Int =
        MySqlSupport.queryOne(
            schema,
            "SELECT amount FROM item_storage WHERE uuid = '$uuid' AND item_id = $itemId"
        ) { it.getInt(1) }

    private fun rowCount(schema: String): Int =
        MySqlSupport.queryOne(schema, "SELECT COUNT(*) FROM item_storage") { it.getInt(1) }

    ////////////////////////////////
    //      前提の確認
    ////////////////////////////////

    @Test
    @DisplayName("テーブルが無ければ db.sql から作成して起動できる")
    fun createsTablesWhenMissing() {

        val f = fixture(schemaSql = null)

        assertTrue(runMigration(f), f.context.logs.dump())

        listOf("item_index", "item_storage", "storage_log", "system_log").forEach { table ->
            assertEquals(
                1,
                MySqlSupport.queryOne(
                    f.schema,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                            "WHERE table_schema = DATABASE() AND table_name = '$table'"
                ) { it.getInt(1) },
                "$table が作成されていません"
            )
        }

        //作成直後は移行済みの形になっている
        assertTrue(hasUniqueIndex(f.schema))
    }

    @Test
    @DisplayName("欠けているテーブルだけを作り、既存のテーブルとデータには触らない")
    fun createsOnlyMissingTables() {

        //storage_log と system_log だけ欠けた状態
        val f = fixture(schemaSql = null)

        MySqlSupport.applySql(
            f.schema,
            MySqlSupport.currentSchemaSql().substringBefore("create table storage_log")
        )

        MySqlSupport.insertStorage(f.schema, uuidA, 1, 777)

        assertTrue(runMigration(f), f.context.logs.dump())

        assertTrue(f.context.logs.anyContains("storage_log"), f.context.logs.dump())
        assertEquals(777, amountOf(f.schema, uuidA, 1), "既存データが変化しました")
        assertEquals(
            1,
            MySqlSupport.queryOne(
                f.schema,
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_name = 'storage_log'"
            ) { it.getInt(1) },
            "storage_log が作成されていません"
        )
    }

    @Test
    @DisplayName("autoMigrateがfalseならテーブルを作らず、db.sqlの実行を促して中止する")
    fun stopsWhenTableMissingAndAutoMigrateDisabled() {

        val f = fixture(schemaSql = null, autoMigrate = false)

        assertFalse(runMigration(f, autoMigrate = false))
        assertTrue(f.context.logs.anyContains("db.sql"), f.context.logs.dump())

        assertEquals(
            0,
            MySqlSupport.queryOne(
                f.schema,
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
            ) { it.getInt(1) },
            "autoMigrate=false なのにテーブルを作成しています"
        )
    }

    @Test
    @DisplayName("CREATE権限が無ければ起動を中止する")
    fun stopsWithoutCreatePrivilege() {

        val f = fixture(
            schemaSql = null,
            user = MySqlSupport.LIMITED_USER,
            password = MySqlSupport.LIMITED_PASSWORD
        )

        assertFalse(runMigration(f))
        assertTrue(f.context.logs.anyContains("CREATE権限"), f.context.logs.dump())
    }

    @Test
    @DisplayName("db.sqlで作った新規DBは移行不要でそのまま起動できる")
    fun freshSchemaNeedsNoMigration() {

        val f = fixture(MySqlSupport.currentSchemaSql())

        assertTrue(runMigration(f), f.context.logs.dump())
        assertTrue(hasUniqueIndex(f.schema))
    }

    ////////////////////////////////
    //      移行
    ////////////////////////////////

    @Test
    @DisplayName("重複が無い旧スキーマにUNIQUEを付け、冗長な非UNIQUEを削除する")
    fun migratesWithoutDuplicates() {

        val f = fixture(MySqlSupport.legacySchemaSql())

        MySqlSupport.insertStorage(f.schema, uuidA, 1, 500)
        MySqlSupport.insertStorage(f.schema, uuidB, 1, 300)

        assertFalse(hasUniqueIndex(f.schema), "前提: 移行前はUNIQUEが無い")
        assertEquals(1, redundantIndexCount(f.schema), "前提: 非UNIQUEの索引がある")

        assertTrue(runMigration(f), f.context.logs.dump())

        assertTrue(hasUniqueIndex(f.schema))
        assertEquals(0, redundantIndexCount(f.schema), "冗長な非UNIQUE索引が残っています")
        assertEquals(500, amountOf(f.schema, uuidA, 1))
        assertEquals(300, amountOf(f.schema, uuidB, 1))
    }

    @Test
    @DisplayName("重複行を合算して最小idの1行へ統合する")
    fun mergesDuplicates() {

        val f = fixture(MySqlSupport.legacySchemaSql())

        //同じ(uuid,item_id)が3行。旧実装ではSELECTが1行目、UPDATEが全行に当たっていた
        MySqlSupport.insertStorage(f.schema, uuidA, 1, 100)
        MySqlSupport.insertStorage(f.schema, uuidA, 1, 250)
        MySqlSupport.insertStorage(f.schema, uuidA, 1, 30)

        //別アイテムの重複も同時に処理できること
        MySqlSupport.insertStorage(f.schema, uuidA, 2, 7)
        MySqlSupport.insertStorage(f.schema, uuidA, 2, 3)

        //重複していない行は触らない
        MySqlSupport.insertStorage(f.schema, uuidB, 1, 42)

        val keepId = MySqlSupport.queryOne(
            f.schema, "SELECT MIN(id) FROM item_storage WHERE uuid = '$uuidA' AND item_id = 1"
        ) { it.getInt(1) }

        assertTrue(runMigration(f), f.context.logs.dump())

        assertEquals(3, rowCount(f.schema), "統合後の行数が想定と違います")
        assertEquals(380, amountOf(f.schema, uuidA, 1), "在庫が合算されていません")
        assertEquals(10, amountOf(f.schema, uuidA, 2))
        assertEquals(42, amountOf(f.schema, uuidB, 1), "重複していない行が変化しています")

        val remainingId = MySqlSupport.queryOne(
            f.schema, "SELECT id FROM item_storage WHERE uuid = '$uuidA' AND item_id = 1"
        ) { it.getInt(1) }
        assertEquals(keepId, remainingId, "最小idの行が残っていません")

        assertTrue(hasUniqueIndex(f.schema))
    }

    @Test
    @DisplayName("統合前の内訳をログに残す(手作業で復元できるようにするため)")
    fun logsStateBeforeMerge() {

        val f = fixture(MySqlSupport.legacySchemaSql())

        MySqlSupport.insertStorage(f.schema, uuidA, 1, 100)
        MySqlSupport.insertStorage(f.schema, uuidA, 1, 250)

        assertTrue(runMigration(f))

        val log = f.context.logs.dump()

        assertTrue(log.contains(uuidA), "uuidがログに出ていません\n$log")
        assertTrue(log.contains("amounts=[100,250]"), "統合前の各在庫がログに出ていません\n$log")
        assertTrue(log.contains("350"), "合計がログに出ていません\n$log")
    }

    @Test
    @DisplayName("2回実行しても在庫が二重計上されない(冪等)")
    fun isIdempotent() {

        val f = fixture(MySqlSupport.legacySchemaSql())

        MySqlSupport.insertStorage(f.schema, uuidA, 1, 100)
        MySqlSupport.insertStorage(f.schema, uuidA, 1, 250)

        assertTrue(runMigration(f))
        assertEquals(350, amountOf(f.schema, uuidA, 1))

        //2回目。何も変わってはいけない
        assertTrue(runMigration(f))
        assertEquals(350, amountOf(f.schema, uuidA, 1), "2回目の実行で在庫が変化しました")
        assertEquals(1, rowCount(f.schema))
    }

    @Test
    @DisplayName("ALTERで中断しても、次回起動で残りから再開し在庫は二重計上されない")
    fun resumesAfterFailedAlter() {

        //ALTER権限の無いユーザーで実行すると、合算まで済んでUNIQUE作成で失敗する
        val schema = MySqlSupport.createSchema("migresume")
        MySqlSupport.applySql(schema, MySqlSupport.legacySchemaSql())

        MySqlSupport.insertStorage(schema, uuidA, 1, 100)
        MySqlSupport.insertStorage(schema, uuidA, 1, 250)

        val limitedContext = TestPluginContext(schema, MySqlSupport.LIMITED_USER, MySqlSupport.LIMITED_PASSWORD)
        val limitedDb = Database(limitedContext.plugin, "limited").also { opened.add(it) }
        check(limitedDb.connect())

        assertFalse(
            Migration.run(limitedDb, limitedContext.logs.logger, true),
            "ALTER権限が無いのに成功しています"
        )

        //合算は済んでいる
        assertEquals(350, amountOf(schema, uuidA, 1))
        assertEquals(1, rowCount(schema))
        assertFalse(hasUniqueIndex(schema))

        //権限のあるユーザーで再開
        val context = TestPluginContext(schema)
        val db = Database(context.plugin, "test").also { opened.add(it) }
        check(db.connect())

        assertTrue(Migration.run(db, context.logs.logger, true), context.logs.dump())

        assertTrue(hasUniqueIndex(schema))
        assertEquals(350, amountOf(schema, uuidA, 1), "再開時に在庫が二重計上されました")
        assertEquals(1, rowCount(schema))
    }

    @Test
    @DisplayName("ALTER権限が無ければ起動を中止し、権限を確認するよう促す")
    fun stopsWithoutAlterPrivilege() {

        val f = fixture(
            MySqlSupport.legacySchemaSql(),
            user = MySqlSupport.LIMITED_USER,
            password = MySqlSupport.LIMITED_PASSWORD
        )

        assertFalse(runMigration(f))
        assertTrue(f.context.logs.anyContains("ALTER権限"), f.context.logs.dump())
    }

    @Test
    @DisplayName("想定外の形で同名インデックスが存在する場合は中止する")
    fun stopsWhenIndexNameTaken() {

        val f = fixture(MySqlSupport.legacySchemaSql())

        //中断された移行で名前だけ残っている状況を作る
        MySqlSupport.execute(
            f.schema,
            "CREATE INDEX item_storage_uuid_item_id_uindex ON item_storage (item_key)"
        )

        assertFalse(runMigration(f))
        assertTrue(f.context.logs.anyContains("SHOW INDEX FROM item_storage"), f.context.logs.dump())
    }

    ////////////////////////////////
    //      autoMigrate
    ////////////////////////////////

    @Test
    @DisplayName("autoMigrateがfalseなら移行せず、手動SQLを案内して中止する")
    fun stopsWhenAutoMigrateDisabled() {

        val f = fixture(MySqlSupport.legacySchemaSql(), autoMigrate = false)

        MySqlSupport.insertStorage(f.schema, uuidA, 1, 100)
        MySqlSupport.insertStorage(f.schema, uuidA, 1, 250)

        assertFalse(runMigration(f, autoMigrate = false))

        assertTrue(f.context.logs.anyContains("db_migration_1.sql"), f.context.logs.dump())
        //勝手に統合していないこと
        assertEquals(2, rowCount(f.schema), "autoMigrate=false なのにデータを変更しています")
        assertFalse(hasUniqueIndex(f.schema))
    }

    @Test
    @DisplayName("autoMigrateがfalseでも移行済みなら起動できる")
    fun startsWhenAlreadyMigratedWithAutoMigrateDisabled() {

        val f = fixture(MySqlSupport.currentSchemaSql(), autoMigrate = false)

        assertTrue(runMigration(f, autoMigrate = false), f.context.logs.dump())
    }

    ////////////////////////////////
    //      移行後の前提
    ////////////////////////////////

    @Test
    @DisplayName("移行後は同じ(uuid,item_id)を二重に作れない")
    fun rejectsDuplicateAfterMigration() {

        val f = fixture(MySqlSupport.legacySchemaSql())

        MySqlSupport.insertStorage(f.schema, uuidA, 1, 100)

        assertTrue(runMigration(f))

        val failed = runCatching { MySqlSupport.insertStorage(f.schema, uuidA, 1, 5) }.isFailure

        assertTrue(failed, "UNIQUE制約が効いていません")
    }
}
