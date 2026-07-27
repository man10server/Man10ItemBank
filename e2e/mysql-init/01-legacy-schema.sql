-- 移行前(このPRより古い)のスキーマ。
-- db.sql との違いは item_storage のインデックスが UNIQUE でないことだけ。
-- 起動時のスキーマ移行を実地で走らせるため、あえてこの状態から始める。

create table item_index
(
	id int auto_increment,
	item_key varchar(128) not null comment 'アイテムの登録名称',
	item_name varchar(128) not null comment 'バニラのアイテム名',
	price double not null comment '現在値(Bid/Askの中間)',
	bid double not null,
	ask double not null,
	tick double not null,
	time datetime default now() not null comment '更新日時',
	disabled boolean default 0 not null comment 'このアイテムを禁止にするか',
	base64 longtext not null,
	constraint item_index_pk
		primary key (id)
);

create index item_index_item_key_disabled_index
	on item_index (item_key, disabled);

create table item_storage
(
	id int auto_increment,
	player varchar(16) not null,
	uuid varchar(36) not null,
	item_id int not null,
	item_key varchar(128) not null,
	amount int default 0 not null,
	time datetime default now() null,
	constraint item_storage_pk
		primary key (id)
);

-- ここが移行対象。UNIQUE ではない
create index item_storage_uuid_item_id_index
	on item_storage (uuid, item_id);

create table storage_log
(
	id int auto_increment,
	item_id int not null,
	item_key varchar(128) null,
	order_player varchar(16) null comment '倉庫を編集したプレイヤー(nullはコンソール)',
	order_uuid varchar(36) null,
	target_player varchar(16) not null,
	target_uuid varchar(36) null,
	action varchar(64) null,
	edit_amount int null comment '操作をした量',
	storage_amount int null comment '編集後の倉庫の量',
	world varchar(64) null,
	x double null,
	y double null,
	z double null,
	time datetime default now() null,
	constraint storage_log_pk
		primary key (id)
);

create table system_log
(
	id int auto_increment,
	player varchar(16) null,
	uuid varchar(36) null,
	action varchar(128) null,
	time datetime default now() null,
	constraint system_log_pk
		primary key (id)
);

-- 移行で統合されるべき重複行。
-- migration_expected ビューではなく、run.sh が下記の期待値を直接検証する。
--   1111... / item_id=1 : 100 + 250 + 30 = 380 (3行 -> 1行)
--   1111... / item_id=2 :   7 +   3      =  10 (2行 -> 1行)
--   2222... / item_id=1 :  42            =  42 (重複なし。触られてはいけない)
insert into item_storage (player, uuid, item_id, item_key, amount, time) values
	('dup_a', '11111111-1111-1111-1111-111111111111', 1, 'legacy_item_1', 100, now()),
	('dup_a', '11111111-1111-1111-1111-111111111111', 1, 'legacy_item_1', 250, now()),
	('dup_a', '11111111-1111-1111-1111-111111111111', 1, 'legacy_item_1',  30, now()),
	('dup_a', '11111111-1111-1111-1111-111111111111', 2, 'legacy_item_2',   7, now()),
	('dup_a', '11111111-1111-1111-1111-111111111111', 2, 'legacy_item_2',   3, now()),
	('solo_b', '22222222-2222-2222-2222-222222222222', 1, 'legacy_item_1', 42, now());
