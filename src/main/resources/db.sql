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

create index item_storage_uuid_item_id_index
	on item_storage (uuid, item_id);

