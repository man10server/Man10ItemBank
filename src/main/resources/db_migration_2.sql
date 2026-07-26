-- ============================================================
--  Man10ItemBank : storage_log を符号付き台帳へ揃えるマイグレーション
--
--  【任意】在庫の整合性には影響しません。
--  storage_log を SUM(edit_amount) で集計したい場合にだけ実行してください。
--
--  ■ 新しい規約
--    edit_amount    : 変動量。増加は正、減少は負。在庫が動かない記録行は0
--    storage_amount : 変動後の在庫。不明な行はNULL
--    action         : 操作の種類(方向はedit_amountの符号で判断する)
--
--  ■ 旧データの状態
--    AddItem                      edit_amount = 正 (そのまま)
--    TakeItem                     edit_amount = 正  ← 減少なのに正。符号反転が必要
--    SetItem                      edit_amount = 設定後の絶対値  ← 変動量ではない
--    CreateStorage                0 / storage_amount = 0 (そのまま)
--    FailedTakingItem(...)        0 / storage_amount = 0  ← 在庫0ではないので嘘
--
--  ■ 今後書き込まれる action (Web側でactionを列挙している場合は追加が必要)
--    増加(edit_amount > 0)
--      AddItem          ItemBankAPI経由の追加
--      PutItem          プレイヤーがメニューから預けた
--      AutoCollect      自動回収キャッシュの書き込み
--      ReturnItem       取り出しに失敗した分を倉庫へ戻した
--      ItemPayReceive   /ipay の受け取り側
--      OpAddItem        /mibop add
--    減少(edit_amount < 0)
--      TakeItem         メニュー/APIからの取り出し
--      ItemPaySend      /ipay の送付側
--      OpTakeItem       /mibop take
--    増減どちらもあり
--      SetItem          /mibop set (変動量を記録する)
--    在庫は動かない記録行(edit_amount = 0, storage_amount = NULL)
--      FailedTakingItem(FullOfInventory) / (Offline) / (NoItemData)
--
--    ※ CreateStorage は出力しなくなりました。倉庫行の作成はupsertに統合され、
--       在庫の変動を伴わないため台帳に残す意味がなくなっています。
--
--  実行前に必ずバックアップを取ってください。
--    mysqldump -u <user> -p <db> storage_log > storage_log_backup.sql
-- ============================================================

-- ------------------------------------------------------------
-- 手順0: 現状の確認。ここに出てくるaction以外があれば、下の手順に足すこと
-- ------------------------------------------------------------
SELECT action,
       COUNT(*)            AS rows_count,
       MIN(edit_amount)    AS min_edit,
       MAX(edit_amount)    AS max_edit,
       MIN(time)           AS first_time,
       MAX(time)           AS last_time
FROM storage_log
GROUP BY action
ORDER BY rows_count DESC;

-- 二重実行の防止用。すでに負のTakeItemがある場合は手順2を実行済み
SELECT COUNT(*) AS already_migrated
FROM storage_log
WHERE action = 'TakeItem' AND edit_amount < 0;

-- ------------------------------------------------------------
-- 手順1: 作業用の退避テーブルを作る(切り戻し用)
-- ------------------------------------------------------------
CREATE TABLE storage_log_backup_20260726 AS SELECT * FROM storage_log;

-- ------------------------------------------------------------
-- 手順2: TakeItem の符号を反転する
--        (0はそのまま。すでに負のものは触らない ＝ 再実行しても安全)
-- ------------------------------------------------------------
UPDATE storage_log
SET edit_amount = -edit_amount
WHERE action = 'TakeItem'
  AND edit_amount > 0;

-- ------------------------------------------------------------
-- 手順3: 記録用の行の storage_amount を NULL にする
--        (在庫0だったわけではないため、LAG等での復元計算を壊す)
-- ------------------------------------------------------------
UPDATE storage_log
SET storage_amount = NULL
WHERE action LIKE 'FailedTakingItem%';

-- ------------------------------------------------------------
-- 手順4(任意): SetItem を変動量へ変換する
--
--   直前の行の storage_amount との差分で復元する。
--   ・ウィンドウ関数を使うためMySQL 8.0以上が必要
--   ・ログに欠落があると誤った値になるため、まず件数と結果を確認すること
--   ・直前の行が無い(その倉庫の最初の記録がSetItem)場合は復元できないので変換しない
--   ・更新対象と同じテーブルを直接JOINするとエラー1093になるため一時テーブルを経由する
-- ------------------------------------------------------------

-- 4-1: 対象件数の確認。0件ならこの手順は不要
SELECT COUNT(*) AS set_item_rows FROM storage_log WHERE action = 'SetItem';

-- 4-2: 変換内容を一時テーブルに作る
CREATE TEMPORARY TABLE tmp_setitem_delta AS
SELECT id,
       storage_amount,
       previous_amount,
       storage_amount - previous_amount AS new_edit_amount
FROM (
         SELECT id,
                action,
                storage_amount,
                LAG(storage_amount) OVER (
                    PARTITION BY target_uuid, item_id ORDER BY time, id
                    ) AS previous_amount
         FROM storage_log
         WHERE storage_amount IS NOT NULL
     ) AS ordered
WHERE action = 'SetItem'
  AND previous_amount IS NOT NULL;

ALTER TABLE tmp_setitem_delta ADD PRIMARY KEY (id);

-- 4-3: 中身を目視確認する(値が妥当か。極端な値が並ぶならログ欠落を疑う)
SELECT t.id,
       l.edit_amount AS current_edit_amount,
       t.previous_amount,
       t.storage_amount,
       t.new_edit_amount
FROM tmp_setitem_delta AS t
         JOIN storage_log AS l ON l.id = t.id
ORDER BY t.id
LIMIT 200;

-- 4-4: 実際に変換する(4-3の結果に納得したうえで実行)
UPDATE storage_log AS l
    JOIN tmp_setitem_delta AS t ON l.id = t.id
SET l.edit_amount = t.new_edit_amount;

DROP TEMPORARY TABLE tmp_setitem_delta;

-- ------------------------------------------------------------
-- 手順5: 検算
--
--   ログの累計と実在庫が一致するか確認する。
--   ※旧実装はログのINSERT失敗を無視し、停止時のフラッシュも無かったため、
--     過去分は欠落している可能性が高い。ここが完全一致しなくても異常ではない。
--     「いつ以降なら一致するか」を把握するための確認と考えること。
-- ------------------------------------------------------------
SELECT s.uuid,
       s.item_id,
       s.item_key,
       s.amount                      AS actual_amount,
       COALESCE(l.total, 0)          AS log_total,
       s.amount - COALESCE(l.total, 0) AS diff
FROM item_storage AS s
         LEFT JOIN (
    SELECT target_uuid, item_id, SUM(edit_amount) AS total
    FROM storage_log
    GROUP BY target_uuid, item_id
) AS l ON l.target_uuid = s.uuid AND l.item_id = s.item_id
WHERE s.amount <> COALESCE(l.total, 0)
ORDER BY ABS(s.amount - COALESCE(l.total, 0)) DESC
LIMIT 200;

-- 差分の全体像(件数と合計のズレ)
SELECT COUNT(*) AS mismatched_storages,
       SUM(s.amount - COALESCE(l.total, 0)) AS total_diff
FROM item_storage AS s
         LEFT JOIN (
    SELECT target_uuid, item_id, SUM(edit_amount) AS total
    FROM storage_log
    GROUP BY target_uuid, item_id
) AS l ON l.target_uuid = s.uuid AND l.item_id = s.item_id
WHERE s.amount <> COALESCE(l.total, 0);

-- ------------------------------------------------------------
-- 切り戻し
-- ------------------------------------------------------------
-- RENAME TABLE storage_log TO storage_log_ng, storage_log_backup_20260726 TO storage_log;
