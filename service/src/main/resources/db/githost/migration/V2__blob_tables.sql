-- The blob store's three tables, moved off the filesystem and into this service's own database.
--
-- THE CANONICAL COPY OF THIS DDL LIVES IN THE LIBRARY, at qits-blobstore's
-- src/main/resources/db/blobstore-tables.sql. A library owns no schema and ships no Flyway
-- migrations, so every consumer copies that file into its own lineage — this migration is the git
-- host's copy, kept VERBATIM so a later diff against the library reads as a diff and not as a
-- rewrite. The library's own suite applies its file unedited on every build, which is what keeps
-- the text below from drifting into prose.
--
-- What points the store at these tables is one key: qits.artifacts.blobs-datasource=githost, in
-- application.properties. It replaces qits.artifacts.blobs-dir, and it puts a pack's bytes on the
-- same datasource as the git_pack / git_pack_file rows that index them — so a pack and its index
-- commit or fail together, which the old blob directory could not promise.

-- Content addressed by a surrogate id, so STAGING and PROMOTED bytes share one chunk table and
-- promote is a state flip rather than a copy. The cascade makes "discard a staging area" one
-- statement.
create table blob_content (
    content_id  uuid primary key,
    state       varchar(16) not null check (state in ('STAGING', 'PROMOTED')),
    started_at  timestamptz not null
);

-- One row per 1 MiB slice. STORAGE EXTERNAL skips TOAST compression: blob content is already
-- compressed (OCI layers, npm tarballs, git packs), so compressing again costs CPU for nothing.
create table blob_chunk (
    content_id  uuid not null references blob_content (content_id) on delete cascade,
    seq         integer not null,
    bytes       bytea not null,
    primary key (content_id, seq)
);
alter table blob_chunk alter column bytes set storage external;

-- The identity row: a SHA-256 content address bound to one content. `stored_at` replaces the file
-- mtime the store used to read for the garbage-collection grace window, and a dedupe does NOT
-- refresh it — parity with the old promote(), which was a no-op when the file already existed.
--
-- varchar, not char: PostgreSQL pads char to its full width. The check restates the store's
-- path-traversal defence at the table; the same rule stays in code.
create table blob (
    id          varchar(64) primary key check (id ~ '^[0-9a-f]{64}$'),
    content_id  uuid not null unique references blob_content (content_id),
    size_bytes  bigint not null,
    chunk_size  integer not null,
    stored_at   timestamptz not null
);

-- The staging sweep reads only STAGING rows, and they are a tiny minority of this table.
create index idx_blob_content_staging on blob_content (started_at) where state = 'STAGING';
