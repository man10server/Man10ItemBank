-- ============================================================
--  Man10ItemBank : 原子的な在庫操作へ移行するためのマイグレーション
--
--  ★ 通常このファイルを手で実行する必要はありません。
--    config.yml の mysql.autoMigrate (既定true) により、
--    同じ内容が起動時に自動実行されます(util/Migration.kt)。
--    自動実行を止めたい場合、または自動実行が失敗した場合にこちらを使います。
--
--  必ずバックアップを取ってから実行してください。
--    mysqldump -u <user> -p <db> item_storage item_index > backup.sql
--
--  item_storage(uuid, item_id) は現状ただのインデックスであり、
--  同じプレイヤー・同じアイテムの行が複数存在しうる状態になっています。
--  この状態では
--    - SELECT は1行目だけを読む
--    - UPDATE は一致する全ての行を更新する
--  ため、在庫が不正になります。重複を統合した上でUNIQUE制約を張ります。
-- ============================================================

-- ------------------------------------------------------------
-- 手順1: 重複の確認 (0件なら手順2・3は不要)
-- ------------------------------------------------------------
SELECT uuid, item_id, COUNT(*) AS rows_count, SUM(amount) AS total_amount
FROM item_storage
GROUP BY uuid, item_id
HAVING rows_count > 1;

-- ------------------------------------------------------------
-- 手順2・3: 重複行の在庫を合算し、余剰行を削除する
--
--  ※この2文は必ずセットで実行すること。
--   手順2だけを2回実行すると、合算済みの値をさらに合算して在庫を二重計上する。
--   そのため明示的にトランザクションで囲んでいる。
-- ------------------------------------------------------------
START TRANSACTION;

-- 手順2: 残す1行(最小id)に合計を入れる
UPDATE item_storage AS s
    JOIN (
        SELECT MIN(id) AS keep_id, SUM(amount) AS total_amount
        FROM item_storage
        GROUP BY uuid, item_id
        HAVING COUNT(*) > 1
    ) AS d ON s.id = d.keep_id
SET s.amount = d.total_amount,
    s.time   = now();

-- 手順3: 集約済みの余分な行を削除する
DELETE s FROM item_storage AS s
    JOIN (
        SELECT MIN(id) AS keep_id, uuid, item_id
        FROM item_storage
        GROUP BY uuid, item_id
        HAVING COUNT(*) > 1
    ) AS d ON s.uuid = d.uuid AND s.item_id = d.item_id
WHERE s.id <> d.keep_id;

COMMIT;

-- ------------------------------------------------------------
-- 手順4: UNIQUE制約を張る (プラグインが起動時にこれを確認します)
--
--  ※ALTER TABLEは暗黙コミットを伴うため、手順2・3のトランザクションとは分ける。
--   UNIQUEを先に作り、冗長になった非UNIQUEインデックスを後から落とす
--   (順序を逆にすると、一時的に索引が無い状態ができる)
-- ------------------------------------------------------------
ALTER TABLE item_storage
    ADD CONSTRAINT item_storage_uuid_item_id_uindex UNIQUE (uuid, item_id);

ALTER TABLE item_storage
    DROP INDEX item_storage_uuid_item_id_index;

-- ------------------------------------------------------------
-- 手順5(任意): ログ検索用のインデックスと列幅の調整
--   - storage_log は監査用途で uuid / 時刻で引くことが多いが索引が無い
--   - world varchar(16) は16文字を超えるワールド名でINSERTが失敗する
-- ------------------------------------------------------------
ALTER TABLE storage_log
    ADD INDEX storage_log_target_uuid_time_index (target_uuid, time);

ALTER TABLE storage_log
    ADD INDEX storage_log_item_id_time_index (item_id, time);

ALTER TABLE storage_log
    MODIFY world varchar(64) NULL;
