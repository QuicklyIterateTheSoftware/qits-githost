# git-storage — a git repository with no directory

`eu.wohlben.qits:qits-artifacts-git-storage`

A JGit `DfsRepository` whose packs, pack indexes and refs are all blobs in a content-addressed
store, listed by a catalog keyed on a repository id. `UploadPack` and `ReceivePack` take a
`Repository`, so the smart-HTTP endpoints that serve a bare origin today serve one of these
unchanged — only whatever *opens* the repository changes.

Six classes, two ports, one dependency: `org.eclipse.jgit`.

    QitsDfsRepositoryBuilder  repositoryId + the two ports -> a repository
    QitsDfsRepository         the repository; caches one ref database, one object database
    QitsDfsObjDatabase        JGit's six DFS methods, answered by the two ports
    QitsDfsReftableDatabase   the ref database: an empty subclass of DfsReftableDatabase
    QitsPackDescription       a pack description that remembers which blob holds each file
    PackDescription/PackFile  the same thing with the JGit taken out — what crosses the ports

## The two ports

`PackBlobStore` stages a readable-writable blob, promotes it to a content address, and locates an
existing one. `PackCatalog` lists, commits and rolls back pack descriptions for a repository id.

Both are declared **here** and implemented **elsewhere**, and the split is deliberate. This module
may not depend on `qits-artifacts-artifacts` — that would be a dependency on another context, which
the repo's CLAUDE.md forbids — and `artifacts` may not depend on this. So the adapters can only live
in `service`, the one module that already depends on both. This module owns the engine and its
contract; it owns neither its bytes nor its rows.

Everything the ports hand out is a JDK type or a record declared here. **No method exposes a type
from `org.eclipse.jgit.internal.*`**, which is what keeps the next paragraph confined to this
directory.

## JGit's internal package, and what a version bump means

Everything here extends `org.eclipse.jgit.internal.storage.dfs`. "Internal" is JGit's own word: that
package carries **no API stability promise between releases**. Abstract methods can be added,
renamed or re-signed in any version, including a patch one, and nothing warns you — the build simply
stops compiling, or worse, compiles against a changed contract.

**This module is therefore the blast radius of a JGit upgrade.** When `jgit.version` moves in the
root pom, check here first and check these specifically:

- **`DfsObjDatabase`'s abstract set.** Today it is six methods — `newPack`, `commitPackImpl`,
  `rollbackPack`, `listPacks`, `openFile`, `writeFile` — plus `getApproximateObjectCount()`, which
  `ObjectDatabase` declares abstract and `DfsObjDatabase` does not fill in. A seventh appearing is a
  compile error; a *default* appearing where an abstract method was is not, and is worth a look.
- **`DfsPackDescription`'s fields.** `PackDescription` carries the ones that must survive a restart:
  source, lastModified, objectCount, deltaCount, min/maxUpdateIndex, indexVersion, and per file the
  size and block size. A new field that JGit relies on and this record does not carry is invisible
  in a single run and wrong after a redeploy.
- **`PackExt` and `PackSource` values.** Both cross the ports as strings, so a new one needs no
  migration — but `QitsPackDescription` drops an extension it does not recognise and reads an
  unknown source as `UNREACHABLE_GARBAGE`, which is deliberately forgiving. Confirm a new extension
  is actually handled rather than silently dropped.
- **`DfsReftableDatabase`.** It is empty-subclassed here. Anything it starts requiring is a change to
  this file and nowhere else.

The tests are the check, not the compiler: `mvn -pl git-storage test` drives the real git CLI through
clone, push, an `--atomic` release push and a repack. If those stay green the upgrade is clean.

## What the suite proves, and what it costs to run

Nothing. It needs no database, no docker, no Quarkus and no network — the two ports are maps, the
server is a JDK `HttpServer` on a loopback port, and the only thing it needs on the machine is a
`git` on the path. That is the repo's "a clone of this repo alone builds and tests green" rule.

Four behaviours are the reason it exists:

- **clone and push** through the real client, with a fresh repository object per request — so nothing
  passes on state held in memory by the instance that wrote it;
- **`--atomic`, all-or-nothing**: a branch the hook refuses takes its tag down with it. JGit's other
  DFS ref backend does not advertise `atomic` at all and a push that passes the flag fails outright,
  which is why the ref database here is reftable;
- **a non-forced push over an existing tag is refused** — the version-uniqueness guarantee the
  release flow rests on;
- **a garbage collection round trip**, which proves the engine and *not* the practice: see below.

## No garbage collection

`DfsGarbageCollector` works against this storage and the platform does not run it. In a store with no
delete a repack does not reclaim — it duplicates. The repacked pack is written; the packs it replaced
are dropped from the catalog and keep their bytes forever. Measured on the platform's largest real
repository: 22 packs and 7.8 MB became 2 packs and 15 MB. One run nearly doubled the footprint.

The accepted cost instead is roughly three blobs per push, forever, which is immaterial against a
blob store measured in gigabytes. `GarbageCollectionTest` asserts the amplification rather than
hiding it, so nobody schedules a repack to save space.

## Two things it is not

- **The git CLI cannot open one of these.** There is no directory to point `--git-dir` at, no worktree
  to add, no config file to write. Every operation is either the wire protocol or in-process JGit.
  That is the point: receive-pack becomes the only writer, so no ref moves without firing
  `post-receive`.
- **`getConfig()` does not persist.** `DfsRepository` answers with a `DfsConfig` whose load and save
  are no-ops, so a per-repository setting written there is read back as the platform default.
  Anything that was a line in a bare's own `config` — `[qits] protectDefaultBranch`, for one — needs
  a row somewhere else.
