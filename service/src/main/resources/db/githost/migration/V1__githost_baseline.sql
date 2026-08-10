-- The git host's own schema, V1, and this service's whole database.
--
-- A FRESH BASELINE, not a copy of qits-platform-artifacts' thirteen migrations. Three of those
-- tables were the git host's — git_pack and git_pack_file (V4) and git_repository_protection (V5) —
-- and the other ten were the artifact store's. The DDL below is those three, carried over column
-- for column so the entity mappings that moved with them still mean what they meant; the lineage
-- restarts because a lineage is owned by a service and this service is new.
--
-- MIGRATING A LIVE HOST IS A DATA MOVE, NOT A SCHEMA ONE. Every repository the platform serves is
-- rows in these tables plus blobs in a store. Cutting over means copying both; nothing here does
-- that, and a fresh deployment starts with no repositories.
--
-- Nothing here is a foreign key into anything. `repository_id` is an opaque string, exactly as the
-- blob store addresses the world by string metadata, and the blob ids below are content addresses
-- in a store this schema knows nothing about.
--
-- THE OUTBOX IS NOT HERE. qits-eventstream owns its own datasource and its own lineage
-- (classpath:db/eventstream/migration) on a database of its own, so a push's events are durable
-- independently of this schema and this file never mentions them.

-- One pack of one repository.
--
-- `pack_name` is a UUID and is NEVER reused: (repository_id, pack_name) is the key for all time, a
-- row is never updated in place, and a name collision would not fail — JGit compares descriptions
-- by name, so it would quietly serve the wrong bytes.
--
-- Three columns must round-trip EXACTLY or refs and objects read wrong after a restart, and none of
-- them fails visibly:
--   last_modified                       sorting key for object lookup and for the reftable stack
--   min_update_index/max_update_index   the reftable stack's primary ordering
--
-- `source` is JGit's PackSource BY NAME rather than an enum or a check constraint, so a JGit
-- version that adds one needs no migration; an unrecognised value is read back as
-- UNREACHABLE_GARBAGE rather than failing the whole repository.
--
-- WE DO NOT GARBAGE COLLECT GIT, and this is where the next person reads it. The blob store has no
-- delete on this path, so DfsGarbageCollector does not reclaim, it DUPLICATES: the repacked pack is
-- written, the packs it replaced lose their rows here, and their bytes stay forever. Measured on
-- the platform's largest real repository, 22 packs and 7.8 MB became 2 packs and 15 MB — one run
-- nearly doubled the footprint. The accepted cost instead is roughly three blobs and three rows per
-- push. Nobody schedules a repack here to save space.
create table git_pack (
    repository_id varchar(255) not null,
    pack_name varchar(128) not null,
    source varchar(64) not null,
    last_modified bigint not null,
    object_count bigint not null,
    delta_count bigint not null,
    min_update_index bigint not null,
    max_update_index bigint not null,
    index_version integer not null,
    primary key (repository_id, pack_name)
);

-- One file of one pack, and the blob that holds it: `pack`, `idx`, `ref`, and whatever a later JGit
-- adds. The extension is a STRING for the same reason `source` is — a catalog row must not carry a
-- JGit enum, or an upgrade that renames one silently changes what stored rows mean.
--
-- `blob_id` is an ordinary BlobStore content address (sha256 hex). Dropping the pack row above frees
-- nothing here and nothing on disk; see the GC note.
create table git_pack_file (
    repository_id varchar(255) not null,
    pack_name varchar(128) not null,
    extension varchar(32) not null,
    blob_id varchar(64) not null,
    file_size bigint not null,
    block_size integer not null,
    primary key (repository_id, pack_name, extension)
);

-- Opening a repository lists every pack it has, and closing a receive-pack deletes the files of the
-- packs it replaced. Both walk one repository, so both want this index.
create index idx_git_pack_file_pack on git_pack_file (repository_id, pack_name);

-- The per-repository default-branch protection override.
--
-- It is a ROW because a DFS-backed repository has no config file: its `getConfig()` is an in-memory
-- DfsConfig whose load and save are no-ops, so reading `[qits] protectDefaultBranch` off the opened
-- repository would silently answer the platform default for every repository, forever, with no
-- symptom. The platform-wide default is still qits.repositories.git.protect-default-branch; an
-- absent row means "no override", exactly as an absent config line did.
create table git_repository_protection (
    repository_id varchar(255) not null,
    protect_default_branch boolean not null,
    updated_at timestamp(6) with time zone not null,
    primary key (repository_id)
);
