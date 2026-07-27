# e2e 検証

実際の Paper サーバーと MySQL を Docker で立て、Man10ItemBank を本番と同じ経路で動かして検証する。

```bash
./e2e/run.sh              # 実行(Paperのダウンロードは再利用)
./e2e/run.sh --clean      # サーバーデータも消してまっさらから
./e2e/run.sh --keep       # 終了後もコンテナを残す(ログを追いたい時)
```

`./gradlew test`（Testcontainers による `Database` / `Migration` の検証）とは対象が別で、
こちらは **実サーバー上でしか確認できないこと** を見る。

## なぜ検証用プラグインが要るのか

`/mibop add|take|set|show` と `/ipay` はいずれも `Bukkit.getPlayer(...)` を使っており、
**オンラインのプレイヤーが居ないと動かない**。RCON からコンソールで叩いても在庫操作には到達しない。

そこで `mibtest/` に e2e 専用プラグインを置き、`ItemData` を直接呼んでシナリオを流す。
メニュー操作（PutMenu / TakeMenu）だけは実クライアントが要るため、ここでは検証できない（手動確認が必要）。

## kotlin-stdlib について

`Man10ItemBank-1.0.jar` には Kotlin ランタイムが入っていない。
実サーバーでは `Man10CommonLibs.jar` などが供給しているクラスを共有して動いている。

e2e でも同じ構造にするため、`MibTest.jar` が kotlin-stdlib を同梱し、
`plugin.yml` の `loadbefore: [Man10ItemBank]` で先に読み込ませて供給側になっている。

つまり **この e2e は「Kotlin を供給する別プラグインが居る」前提を再現している**。
供給元が居ない環境では Man10ItemBank は起動しない。

## 検証している内容

### 起動時
- `libraries:` の `mysql-connector-j` / `HikariCP` が実際に解決できる
- MySQL へ接続しプールを初期化できる
- クラス未解決（`NoClassDefFoundError`）が出ない
- プラグインが無効化されない

### スキーマ移行
`mysql-init/01-legacy-schema.sql` が **移行前のスキーマ（非UNIQUE索引）と重複行** を投入するので、
起動時に実際の移行処理が走る。

| uuid | item_id | 投入 | 期待 |
|---|---|---|---|
| `1111…` | 1 | 100 / 250 / 30 の3行 | 380 の1行 |
| `1111…` | 2 | 7 / 3 の2行 | 10 の1行 |
| `2222…` | 1 | 42 の1行（重複なし） | 42 のまま |

- 統合前の内訳がログに残る（手作業で復元できること）
- `UNIQUE(uuid, item_id)` が作られ、冗長な非UNIQUE索引が消える
- 再起動しても二重に統合されない（冪等）

### 在庫操作（`mibtest run`）
- 倉庫行が upsert で1行だけ作られる／相対更新が積み上がる
- 在庫不足・上限超過は DB を変更せずに `Insufficient`
- `setItemAmount` が `storage_log` に **変動量** を残す（絶対値ではない）
- 送付は両側が同一トランザクション。受け取り側が上限でも **送付元の在庫が消えない**
- 自分自身への送付・未登録アイテム・0個の送付は `Error`
- `SUM(storage_log.edit_amount)` が実在庫と一致する
- 8スレッド×25回の並列相対更新でロストアップデートが起きない
- 自動回収キャッシュが DB へ書かれる

### 停止時
- 未処理の在庫操作を残さずキューを処理し切る
- 書き込めなかったログが無い

## ここでは検証していないこと

- **メニュー操作**（PutMenu / TakeMenu の現物受け渡し、インベントリ満杯時の挙動）— 実クライアントが必要
- **`/ipay` のコマンド経路** — Player 送信者とオンラインの相手が必要（`ItemData.transferItem` 自体は検証済み）
- **DB 障害中の挙動** — 手動なら以下で確認できる

```bash
./e2e/run.sh --keep
cd e2e
docker compose stop mysql
docker compose exec -T paper rcon-cli mibtest run   # 全て Error になり、サーバーは落ちないこと
docker compose start mysql
docker compose exec -T paper rcon-cli mibtest run   # 復帰すること
```

## ポート

開発機の既存環境とぶつからないようにずらしてある。

| | ホスト側 |
|---|---|
| MySQL | 13306 |
| Minecraft | 25599 |
| RCON | コンテナ内のみ（`docker compose exec -T paper rcon-cli`） |
