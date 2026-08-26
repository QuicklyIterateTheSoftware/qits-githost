-- Lines-of-code summaries, one row per (repository, commit) a summary has been computed for.
--
-- A ROW IS AN IMMUTABLE MEMO. The summary is a pure function of the commit's tree — same commit,
-- same numbers, forever — so a row is written once, never updated, and a concurrent duplicate
-- insert is a harmless race between two writers holding identical payloads. `payload` is the JSON
-- the browse endpoint answers, stored verbatim so a memo hit is a passthrough rather than a
-- re-serialization.
--
-- GROWTH IS BOUNDED BY THE WRITER, NOT BY A SWEEPER. Every write prunes its repository down to the
-- newest rows by computed_at (see RepositoryLocStore.KEPT_PER_REPOSITORY), which keeps the branch
-- tips everyone browses hot while shas nobody asks about anymore age out. Losing a row costs one
-- rescan.
--
-- Nothing here is a foreign key into anything, per this schema's rule: repository_id is the same
-- opaque string the pack tables hold, and commit_sha addresses an object in a store this schema
-- knows nothing about.
create table git_repository_loc (
    repository_id varchar(255) not null,
    commit_sha    varchar(64)  not null,
    payload       text         not null,
    computed_at   timestamptz  not null,
    primary key (repository_id, commit_sha)
);
