#!/usr/bin/env bash
#
# Man10ItemBank の e2e 検証。
#
#   ./e2e/run.sh            前回のPaperダウンロードを再利用して実行
#   ./e2e/run.sh --clean    サーバーデータも消してまっさらから実行
#   ./e2e/run.sh --keep     終了後もコンテナを残す(ログを見たい時)
#
# 前提: docker / docker compose が使えること。初回は Paper と libraries の
#       ダウンロードで外部通信が発生する。
#
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
cd "$HERE"

CLEAN=0
KEEP=0
for arg in "$@"; do
    case "$arg" in
        --clean) CLEAN=1 ;;
        --keep)  KEEP=1 ;;
        *) echo "不明な引数: $arg"; exit 2 ;;
    esac
done

COMPOSE="docker compose"
MYSQL="$COMPOSE exec -T mysql mysql -uitembank -pitembank itembank -N -B"
RCON="$COMPOSE exec -T paper rcon-cli"

FAILURES=0

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m[OK]\033[0m %s\n' "$*"; }
ng()   { printf '  \033[31m[NG]\033[0m %s\n' "$*"; FAILURES=$((FAILURES + 1)); }
info() { printf '       %s\n' "$*"; }

cleanup() {
    if [ "$KEEP" -eq 1 ]; then
        step "コンテナは残しています ( $COMPOSE -f $HERE/docker-compose.yml down -v で削除 )"
    else
        step "後片付け"
        $COMPOSE down -v --remove-orphans >/dev/null 2>&1
    fi
}
trap cleanup EXIT

# 期待する "Done (" の出現回数に達するまで待つ。
# 再起動後もログには前回分が残るため、回数で判定する。
wait_for_server() {
    local want="$1" timeout="${2:-420}" start=$SECONDS count
    while (( SECONDS - start < timeout )); do
        count=$($COMPOSE logs paper 2>/dev/null | grep -c 'Done (' || true)
        if [ "$count" -ge "$want" ]; then
            return 0
        fi
        if ! $COMPOSE ps --status running --format '{{.Service}}' 2>/dev/null | grep -qx paper; then
            ng "paperコンテナが停止しました"
            $COMPOSE logs --tail 40 paper
            return 1
        fi
        sleep 3
    done
    ng "サーバーが${timeout}秒以内に起動しませんでした"
    $COMPOSE logs --tail 40 paper
    return 1
}

paper_logs() { $COMPOSE logs paper 2>/dev/null; }

expect_sql() {
    local what="$1" expected="$2" sql="$3" actual
    actual=$($MYSQL -e "$sql" 2>/dev/null | tr -d '[:space:]')
    if [ "$actual" = "$expected" ]; then
        ok "$what = $actual"
    else
        ng "$what : expected=$expected actual=${actual:-<空>}"
    fi
}

########################################
# 1. ビルド
########################################
step "プラグインをビルド"

if ! (cd "$ROOT" && ./gradlew build -q --console=plain); then
    ng "Man10ItemBank のビルドに失敗しました"
    exit 1
fi
ok "Man10ItemBank-1.0.jar"

if ! (cd "$ROOT" && ./gradlew -p e2e/mibtest build -q --console=plain); then
    ng "MibTest のビルドに失敗しました"
    exit 1
fi
ok "MibTest-1.0.jar (kotlin-stdlib同梱)"

########################################
# 2. サーバーデータの準備
########################################
step "サーバーデータを準備"

$COMPOSE down -v --remove-orphans >/dev/null 2>&1

if [ "$CLEAN" -eq 1 ]; then
    rm -rf data
    info "data/ を削除しました(Paperを再ダウンロードします)"
fi

# プラグインとワールドは毎回まっさらにする(DBはコンテナごと作り直される)
rm -rf data/plugins data/world data/world_nether data/world_the_end data/logs
rm -f data/mibtest-result.txt
mkdir -p data/plugins/Man10ItemBank data/plugins/MibTest

cp "$ROOT/build/libs/Man10ItemBank-1.0.jar" data/plugins/
cp "$ROOT/e2e/mibtest/build/libs/MibTest-1.0.jar" data/plugins/
cp plugin-config/Man10ItemBank-config.yml data/plugins/Man10ItemBank/config.yml
ok "plugins/ を配置"

########################################
# 3. 起動 (1回目) — スキーマ移行が走る
########################################
step "コンテナ起動 (1回目: スキーマ移行)"

if ! $COMPOSE up -d; then
    ng "コンテナを起動できませんでした"
    exit 1
fi

wait_for_server 1 || exit 1
ok "サーバー起動完了"

########################################
# 4. 起動時の検証
########################################
step "起動処理の検証"

LOGS=$(paper_logs)

if grep -q 'MySQL接続プールを初期化しました' <<<"$LOGS"; then
    ok "MySQLへ接続しプールを初期化した"
else
    ng "接続プールの初期化ログがありません"
fi

if grep -q 'Disabling Man10ItemBank' <<<"$LOGS"; then
    ng "Man10ItemBank が無効化されています"
    grep -E 'Man10ItemBank|SEVERE' <<<"$LOGS" | tail -20
else
    ok "Man10ItemBank は無効化されていない"
fi

# plugin.yml の libraries: が実際に解決できたか (PRの未検証事項)
if grep -qE 'mysql-connector-j|HikariCP' <<<"$LOGS" || \
   { [ -d data/libraries ] && find data/libraries -name '*mysql-connector-j*' | grep -q .; }; then
    ok "libraries: の依存が解決された"
else
    ng "libraries: の解決を確認できませんでした"
fi

if grep -q 'NoClassDefFoundError\|ClassNotFoundException' <<<"$LOGS"; then
    ng "クラス未解決のエラーがあります"
    grep -E 'NoClassDefFoundError|ClassNotFoundException' <<<"$LOGS" | head -5
else
    ok "クラス未解決のエラーなし"
fi

########################################
# 5. スキーマ移行の検証
########################################
step "スキーマ移行の検証"

if grep -q '重複した倉庫行を2組検出しました' <<<"$LOGS"; then
    ok "重複行を検出した"
else
    ng "重複行の検出ログがありません"
fi

# 統合前の内訳がログに残っていること(手動復旧のため)
if grep -q 'amounts=\[100,250,30\]' <<<"$LOGS"; then
    ok "統合前の内訳をログに残した"
else
    ng "統合前の内訳がログにありません"
fi

if grep -q 'スキーマ移行が完了しました' <<<"$LOGS"; then
    ok "移行完了"
else
    ng "移行完了のログがありません"
fi

expect_sql "重複統合後の在庫 (100+250+30)" "380" \
    "SELECT amount FROM item_storage WHERE uuid='11111111-1111-1111-1111-111111111111' AND item_id=1"
expect_sql "重複統合後の在庫 (7+3)" "10" \
    "SELECT amount FROM item_storage WHERE uuid='11111111-1111-1111-1111-111111111111' AND item_id=2"
expect_sql "重複していない行は不変" "42" \
    "SELECT amount FROM item_storage WHERE uuid='22222222-2222-2222-2222-222222222222' AND item_id=1"
expect_sql "統合後の行数" "3" "SELECT COUNT(*) FROM item_storage"
expect_sql "UNIQUE(uuid,item_id) が作られた" "1" \
    "SELECT COUNT(*) FROM (SELECT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='item_storage' AND non_unique=0 GROUP BY index_name HAVING COUNT(*)=2 AND SUM(column_name='uuid')=1 AND SUM(column_name='item_id')=1) t"
expect_sql "冗長な非UNIQUE索引は削除された" "0" \
    "SELECT COUNT(*) FROM (SELECT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='item_storage' AND non_unique=1 GROUP BY index_name HAVING COUNT(*)=2 AND SUM(column_name='uuid')=1 AND SUM(column_name='item_id')=1) t"

# 移行前スキーマには storage_log / system_log が無い。db.sqlから自動作成されること
if grep -q 'テーブルを作成しました' <<<"$LOGS"; then
    ok "欠けていたテーブルを db.sql から作成した"
else
    ng "テーブル自動作成のログがありません"
fi

expect_sql "storage_log が作成された" "1" \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='storage_log'"
expect_sql "system_log が作成された" "1" \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='system_log'"

########################################
# 6. 検証用アイテムの登録 → 再起動
########################################
step "検証用アイテムを登録し、再起動で読み込ませる"

$RCON mibtest seed >/dev/null 2>&1

for _ in $(seq 1 20); do
    if paper_logs | grep -q 'SEED inserted\|SEED already-present'; then break; fi
    sleep 1
done

if paper_logs | grep -q 'SEED inserted\|SEED already-present'; then
    ok "item_index へ登録した"
else
    ng "seed が完了しませんでした"
fi

# 再起動は onDisable → onEnable の検証も兼ねる
$COMPOSE restart paper >/dev/null 2>&1
wait_for_server 2 || exit 1
ok "再起動完了"

LOGS=$(paper_logs)

# 移行済みなら Migration.run は重複チェックごと飛ばして早期に返る。
# 2回目の起動で移行処理が再度走っていないことを、出現回数で確認する。
MIGRATION_RUNS=$(grep -c 'スキーマ移行を開始します' <<<"$LOGS" || true)
MERGE_RUNS=$(grep -c '重複した倉庫行を' <<<"$LOGS" || true)

if [ "$MIGRATION_RUNS" = "1" ] && [ "$MERGE_RUNS" = "1" ]; then
    ok "2回目の起動では移行が再実行されない(冪等)"
else
    ng "移行が複数回実行されています (開始=${MIGRATION_RUNS}回 統合=${MERGE_RUNS}回)"
fi

expect_sql "再起動後も在庫は不変" "380" \
    "SELECT amount FROM item_storage WHERE uuid='11111111-1111-1111-1111-111111111111' AND item_id=1"

########################################
# 7. 在庫操作のシナリオ
########################################
step "在庫操作の検証を実行"

$RCON mibtest run >/dev/null 2>&1

for _ in $(seq 1 120); do
    [ -f data/mibtest-result.txt ] && grep -q 'RESULT' data/mibtest-result.txt && break
    sleep 2
done

if [ -f data/mibtest-result.txt ] && grep -q 'RESULT' data/mibtest-result.txt; then
    echo
    sed 's/^/  /' data/mibtest-result.txt
    echo
    RESULT_LINE=$(grep 'RESULT' data/mibtest-result.txt)
    FAIL_COUNT=$(sed -E 's/.*fail=([0-9]+).*/\1/' <<<"$RESULT_LINE")
    if [ "${FAIL_COUNT:-1}" = "0" ]; then
        ok "$RESULT_LINE"
    else
        ng "$RESULT_LINE"
    fi
else
    ng "検証結果が出力されませんでした"
    $COMPOSE logs --tail 40 paper
fi

########################################
# 8. 停止時のドレイン
########################################
step "停止時にキューが処理し切られるか"

$COMPOSE stop paper >/dev/null 2>&1

LOGS=$(paper_logs)

if grep -q '未処理の在庫操作が' <<<"$LOGS"; then
    ng "停止時に未処理の在庫操作が残りました"
    grep '未処理の在庫操作が' <<<"$LOGS" | tail -3
else
    ok "未処理の在庫操作は残らなかった"
fi

if grep -q 'ログ[0-9]*件を書き込めませんでした' <<<"$LOGS"; then
    ng "停止時に書き込めなかったログがあります"
else
    ok "書き込めなかったログは無い"
fi

########################################
# 9. まとめ
########################################
step "まとめ"

if [ "$FAILURES" -eq 0 ]; then
    printf '  \033[32m全ての検証を通過しました\033[0m\n'
    exit 0
else
    printf '  \033[31m%d件の検証に失敗しました\033[0m\n' "$FAILURES"
    exit 1
fi
