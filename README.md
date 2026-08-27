# qits-githost

The platform's git smart-HTTP host. Every repository qits serves is here: workspace containers
clone and push over HTTP, qits-ci reads a pipeline config out of one file, qits-workspaces releases
through it, and a push announces itself to the platform as a durable domain event.

It also serves a page. Since the client landed, this is not a wire-protocol-only service: it carries
its own Angular SPA — served at `/` on this service's own host, `githost.<env>.<domain>` — and the
REST API that SPA reads at `/githost/api`, in the same process and on the same port as the git
protocol at `/git`. Both planes land on the authority every clone url already spells; the edge picks
the gate per request.

It moved out of `qits-platform-artifacts` with its history (byte-plane-split-plan.md phase 3). A git
repository is not an artifact — it only shared the blob store's storage layout — and every consumer
is an env service, so an env-scoped git host is the consistent shape.

## Modules

| Module | What it is |
|---|---|
| `git-storage` | The storage engine: a JGit `DfsRepository` whose packs, pack indexes and reftables are content-addressed blobs. A plain library jar with ONE compile dependency (JGit) and two ports it declares and does not implement. |
| `githost-events` | The event vocabulary. Depends on `qits-eventstream` and nothing else. **This is the jar a consumer depends on.** |
| `service` | The deployable: the Vert.x routes, the REST API, the Angular client (`src/main/webui`, the `qits-spa-githost` submodule, served by Quinoa), the two port adapters over `qits-blobstore`, the schema, and the publisher. |

## Addresses

Four surfaces, one port, one host. **`githost.<env>.<domain>` is this service's own host**, and it
serves the client at its root. `/githost` stays this service's machine segment and `/git` the wire
protocol's; the edge path-routes both on every host, so nothing that names them has to move.

| Address | What it is |
|---|---|
| `/git/**` | The git wire protocol. Plain Vert.x routes, the prefix a literal in `GitHostRoutes` — git treats the base as opaque, so no config key can move it. |
| `/githost/api/**` | The REST API (`quarkus.rest.path`), read by the client and by nothing that speaks git. |
| `/` | The Angular client, built and served by Quinoa out of `service/src/main/webui` (the `qits-spa-githost` submodule). The old bare-`/githost` trailing-slash wart (upstream quinoa #960) went with the move to the root. |
| `/githost/q/**` | Quarkus' non-application root: health, and nothing else here. |

Because the client sits at the root, `/git` and `/bootstrap-git` are inside the SPA fallback's reach
for the first time. `quarkus.quinoa.ignored-path-prefixes` therefore lists all three absolutely —
`/githost,/git,/bootstrap-git` — so a mistyped machine path is a 404 rather than a web page a git
client would read as a ref advertisement.

It **was** `/git/q` for the non-application root, when `/git` was read as the whole segment. The
health gate in `.config/qits/deployments.yml` moved with it.

### The git routes

| Route | What it does |
|---|---|
**The name-addressed scheme is the public one.** `/git/<projectId>/<repoName>` is the clone url every
consumer holds — CI, the daemons, deploy pushes, humans. `/git/<repoId>` is **internal storage
plumbing**, spoken by qits-projects and nothing else: it is what mints and mirrors, and a UUID clone
url is never published. With `qits.githost.storage-client` configured, the id-addressed scheme is
served only to that client's self-role (see Configuration).

| Route | What it does |
|---|---|
| `GET /git/:projectId/:repoName/info/refs?service=…` | The ref advertisement, name-addressed. |
| `POST /git/:projectId/:repoName/git-upload-pack` | Fetch / clone. |
| `POST /git/:projectId/:repoName/git-receive-pack` | Push. |
| `GET /git/:projectId/:repoName/blob/:rev/<path>` | The raw bytes at that path in that revision. `Git-Commit-Sha` names the resolved commit. |
| `GET /git/:projectId/:repoName/tree/:rev[/<path>]` | `{"entries":[{"name","type"}]}` for the directory there. |
| `GET /git` | `{"repositories": ["<repoId>", …]}` — every repository this host serves, sorted. Storage ids. |
| `PUT /git/:repoId` | Create, idempotently. Body `{"defaultBranch": "main"}`. 201 created, 200 already there. |
| `GET /git/:repoId` | `{"repoId", "defaultBranch"}`, or 404. |
| `HEAD /git/:repoId` | The same existence question, no body. |
| `DELETE /git/:repoId` | Delete the repository. 204 gone, 404 no such repository. No body. |
| `GET /git/:repoId/info/refs?service=git-(upload\|receive)-pack` | The ref advertisement, id-addressed. |
| `POST /git/:repoId/git-upload-pack` | Fetch, id-addressed. |
| `POST /git/:repoId/git-receive-pack` | Push, id-addressed. |
| `GET /git/:repoId/blob/:rev/<path>` | The same blob read, id-addressed. |
| `GET /git/:repoId/tree/:rev[/<path>]` | The same tree read, id-addressed. |
| `GET /githost/q/health/ready` | Readiness, for qits-cd's health gate. |

The content reads carry a literal segment (`blob`, `tree`) and a tail that may hold slashes, so path
length does not separate the two schemes there. The name-addressed pair is registered first and hands
a request down when the name does not resolve; the id-addressed pair hands a
`/git/<project>/<blob|tree>/info/refs` clone back to the name-addressed route. So a repository is
never unreachable because of what it is called, and no shape is answered twice.

**The prefix changed.** It was `/artifacts/git` while the host lived inside qits-artifacts; standing
alone it drops the borrowed segment. qits-gateway routes verbatim by prefix, so `/git` is carried as
an extra prefix on this service's entry, and every client that names the old path has to be moved
with the cutover.

The name-addressed scheme resolves `(projectId, repoName)` through qits-projects
(`qits.projects.name-resolver-url`) and is what makes a committed relative submodule url
(`../<name>.git`) work. Unset, that scheme answers 404 and the id-addressed one keeps serving.

**A resolver miss and a resolver outage are different answers.** qits-projects' 404 means "no such
name" and reaches the client as a 404; anything that stopped the question being answered — a timeout,
a refused connection, a non-200, an unreadable body — is a **503**. A git client records a 404 as
"this repository is gone", so an outage answered that way would tell the platform every repository
had been deleted (the `fe26a6c` lesson).

### The API

| Route | What it does |
|---|---|
| `GET /githost/api/repositories` | `{"repositories":[{"id", "protectDefaultBranch"}, …]}` — every repository this host serves, sorted, as records. |

It answers the same question as `GET /git` and is not a duplicate of it: that one is a wire the
platform's machines read and its shape is fixed by them, this one is the browser's and may grow a
field. Both are **storage views** — the ids are opaque storage keys, not clone urls, and a repository's
public identity (`projectId`, `repoName`) lives in qits-projects. **`id` is the whole contract**; the client renders anything else that arrives and assumes
nothing about it, so a field is added here when it is honest and cheap and never as a placeholder.
`protectDefaultBranch` is the effective answer — the platform switch with the repository's override
applied — which is why it is not simply "there is a row".

**A read this API cannot make is a 5xx, never an empty list and never a 404.** Both halves of it
throw rather than fall back, which is the 2026-08-11 lesson (`fe26a6c`) applied one surface further
out: a page told "no repositories" shows an empty host, and nothing on it says the service could not
ask.

### The client

`service/src/main/webui` is the `qits-spa-githost` submodule, an Angular 21 SPA that Quinoa builds
during `mvn package` and serves at `/`. Its `baseHref` is `"/"` and the pairing is spelled in two
repositories — here as `quarkus.quinoa.ui-root-path`, there in `angular.json` — so a mismatch serves
a page whose every asset 404s. `docs/project-setup-quinoa-angular.md` in the superproject is
the doctrine; `application.properties` carries the per-key reasoning.

## Events

A push publishes through `QitsEventBus` — the qits-eventstream outbox, so a consumer that was down
while the push landed reads the event back. This replaces the post-receive HTTP fan-out, which
retried in memory for about three minutes and then logged the loss.

Depend on `eu.wohlben.qits:qits-githost-events` for the vocabulary. Wire name = simple class name.

| Event | When | Payload |
|---|---|---|
| `SCMPublishCommit` | per successfully updated branch ref | `repoId`, `projectId`, `repoName`, `branch`, `oldSha`, `sha`, `parents[]`, `authorName`, `authorEmail`, `authoredAt`, `committedAt`, `message`, `suppressCi`, `receivedAt` |
| `SCMPublishTag` | per created or updated tag ref | `repoId`, `projectId`, `repoName`, `tagName`, `sha`, `targetSha`, `taggerName`, `taggerEmail`, `message`, `annotated`, `receivedAt` |
| `SCMDeleteBranch` | per deleted branch ref | `repoId`, `projectId`, `repoName`, `branch`, `sha` (the old tip), `receivedAt` |
| `SCMDeleteTag` | per deleted tag ref | `repoId`, `projectId`, `repoName`, `tagName`, `sha` (the old tip), `receivedAt` |

**`projectId` and `repoName` are the address the push arrived on**, echoed and not resolved: the
public clone url carries both, so the route already holds them when it announces and this host still
looks nothing up and stores no name. They are **null — omitted from the payload — for a push on the
id-addressed scheme**, which is qits-projects mirroring history the platform already announced. A
consumer that needs a name ignores those events. Both keys are additive: an older payload simply has
neither.

`occurredAt` is `receivedAt` on all four: when this host finished taking the push. A commit's own
two clocks (`authoredAt`, `committedAt`) are the pusher's and stay in the payload.

Three things are new against the old `{repoId, branch, oldSha, newSha}` body:

- **Tags leave the host at all.** The fan-out filtered to `refs/heads/*`.
- **Deletions are announced.** The fan-out skipped every `DELETE`.
- **`-o qits.no-ci` is a field, not a decision.** It becomes `suppressCi` and every consumer decides
  what that means to it. The notifier used to skip the CI POST and send the projects one, which put
  the option's meaning in the publisher.

A refused ref publishes nothing: it did not move.

### Causation

A push may name the event it is being made **because of**, in the `X-Qits-Causation-Id` header
(`CausationHeader.NAME`). Every event the push publishes is then stamped with that id as the
envelope's `parentId`, so a chain that reaches the git host — a workspace integrate, a bot acting on
an announcement — keeps going into the SCM events rather than restarting at them.

qits-eventstream propagates a cause with a pair of JAX-RS filters, and **a push is not a JAX-RS
request** — the git routes are raw Vert.x, which no filter sees. So `GitHostRoutes.causationOf`
reads the header itself and wraps the post-receive announcement in `CausationScope.with(...)`.
(There is a JAX-RS surface here now, at `/githost/api`, where those filters do apply; it publishes
nothing, so nothing about the push path changed.) Blank and malformed both read as absent: causation is
advisory and a push is never refused over it. Only the receive-pack path reads it — the content GETs
publish nothing.

A caller building the request by hand stamps it itself:

```java
UUID cause = CausationScope.current();
if (cause != null) builder.header(CausationHeader.NAME, cause.toString());
```

and `git` itself can carry one with `-c "http.extraHeader=X-Qits-Causation-Id: <uuid>"`.

The header sits inside the gateway's reserved `X-Qits-` namespace, which qits-gateway strips from
client traffic — so a cause cannot be forged from outside, and service-to-service traffic on qits-net
carries it untouched.

### Native images

`bus/EventWireReflection` registers the four records plus `EventEnvelope` (and, by name, the
canonical mapper's private mix-in) for reflection. Without it a native binary publishes nothing and
says so only in a WARN per push: `CanonicalJson` builds its own `ObjectMapper`, so no build step
knows those types are serialized. A fifth event means a fifth entry, and
`EventWireReflectionTest` is what fails when it is missing.

## Configuration

The library jars ship their own defaults at ordinal 100 (`qits-blobstore`: the blob datasource, the
grace period and the staging TTL; `qits-eventstream`: the bus url, the outbox datasource and
lineage, the retry budget). What this service owns is in
`service/src/main/resources/application.properties`.

| Key / variable | Meaning |
|---|---|
| `QITS_RESOURCE_DB_URL` / `_USERNAME` / `_PASSWORD` | This service's PostgreSQL database (pack catalog, protection overrides). No defaults — an unset variable fails the boot. |
| `QITS_RESOURCE_EVENTSTREAM_URL` / `_USERNAME` / `_PASSWORD` | The outbox's own database, from the qits-eventstream jar. |
| `QITS_EVENTS_URL` | Where qits-events answers. Scheme, host and port, no path. |
| `QITS_PROJECTS_NAME_RESOLVER_URL` | Where qits-projects resolves a repository name. No default; unset means the name-addressed scheme 404s — which is every public clone url, so a real deployment sets it. |
| `QITS_GITHOST_STORAGE_CLIENT` | The client id whose self-role (`clients/<value>`) opens the id-addressed scheme. Set it to qits-projects' service client and nothing else opens those routes — not `qits:admin`, not `qits:system`. Unset (shipped) leaves the scheme exactly as it was. |
| `QITS_REPOSITORIES_GIT_PROTECT_DEFAULT_BRANCH` | The default branch's seatbelt. Ships `false`. |
| `QITS_REPOSITORIES_GIT_PUSH_TOKEN` | What `-o qits.token=<value>` must match. No default: unset means no token matches. |
| `QITS_REPOSITORIES_GIT_MAX_PACK_SIZE` | The largest push this host accepts. Ships `64M`. |

There is no variable for where the bytes go any more. `qits.artifacts.blobs-datasource=githost`
points the blob store at this service's own database, and a deployment has no reason to move it.

Push options, all read inside the pack protocol rather than as headers (qits-gateway strips the
whole `X-Qits-` prefix, so a header would behave differently through the front door):

- `-o qits.release` — an integrate-produced release. Fast-forward only.
- `-o qits.token=<value>` — push the protected branch anyway, if the value matches.
- `-o qits.no-ci` — not a bypass. It rides through to `SCMPublishCommit.suppressCi`.

## Deployment

A push builds `docker/Dockerfile` — a Mandrel builder stage that native-compiles `service`, a
`ubi-minimal` runtime stage that carries only the binary — and pushes it as
`qits/qits-githost:<sha>`; a release rebuilds the same content under the released version
(`.config/qits/ci-event-build.yml` and `.config/qits/ci-event-release.yml` — this repository
carries **no `ci-post-receive.yml`**: every pipeline is a domain-event trigger, the push build
riding `SCMPublishCommit` with `checkout:` so it still builds the pushed branch at the pushed sha.
A third file, `ci-event-userflows.yml`, runs `mvn verify` per commit and publishes the userflow
reports as the `@userflows/qits-githost` docs site, version = the commit sha, non-gating for the
image). Both image builds run
`--network host` with `--build-arg QITS_MAVEN_REPOSITORY_URL=…`, because `qits-eventstream`
exists only in the platform's own Maven repository and a docker build reaches no other address for
it.

**Each pipeline is one step with two halves**, and the split is not cosmetic: the client depends on
`@qits/ui-components`, which lives only on the platform's own npm registry, and a docker `RUN` can
reach that registry by no address at all. So the step container builds the bundle first, and the
image build packages one that already exists — its Quinoa install/ci/build commands are neutered to
`--version`, and a missing bundle is a red build at a `test -f` guard rather than an image that
boots and serves `/` as a 404.

`.config/qits/deployments.yml` is the deploy answer: **an environment service** — every tier
runs its own git host, and a green build deploys into whichever tier listens to the built branch —
with `resources: postgresql:db, postgresql:eventstream:qits_githost_eventstream` and the health gate
at `/githost/q/health/ready`. Those two resource **names** are load-bearing: they are what makes
`QITS_RESOURCE_DB_*` and `QITS_RESOURCE_EVENTSTREAM_*` exist, and neither triple has a default, so a
missing one kills the boot at Flyway rather than opening a store nobody meant. **There is no blobs
volume any more**: the container is stateless except for its two databases, so a deployment still
mounting one is carrying dead bytes. Deploy this service alone: replacing it blinks the host every
other repository's build clones from.

## Storage

A repository has no directory anywhere, and no file anywhere either. Its packs, pack indexes and
reftables are blobs in this service's own content-addressed store; the pack list is rows in
`git_pack` / `git_pack_file`. So receive-pack is the only writer, and no ref moves without the
post-receive hook firing.

**The blobs are rows too, on the same database.** `V2__blob_tables.sql` adds the store's three
tables — `blob`, `blob_content` and `blob_chunk` — and it is a **verbatim copy** of `qits-blobstore`'s
`src/main/resources/db/blobstore-tables.sql`, because a library owns no schema and ships no Flyway
migrations: the canonical text lives there and each consumer copies it into its own lineage, which
keeps a later drift readable as a diff. `qits.artifacts.blobs-datasource=githost` is what points the
store at them.

**So this service is stateless and the container holds nothing.** A pack's bytes and the row that
indexes it commit or fail together — the split-brain a blob directory invited, a pack file whose row
did not survive or a row whose file did not, cannot happen. A restart loses nothing.

A pack is written through a **scratch blob**, not a temp file: JGit's pack parser reads deltas back
out of a pack it has not finished storing, so `BlobStorePackBlobStore` stages into `blob_chunk` rows
it can also read from — flushed chunks from the database, the unflushed tail from the one buffered
chunk in memory. `ScratchBlob.openRead()` seals the staging (it writes the final short chunk, and a
write after it throws), which is why the adapter hashes before promoting and never the other way
round.

**The platform does not garbage collect git**, deliberately. The blob store has no delete on this
path, so `DfsGarbageCollector` does not reclaim, it duplicates — measured once on the platform's
largest repository, 22 packs and 7.8 MB became 2 packs and 15 MB. The accepted cost instead is about
three blobs and three rows per push.

**A repository can be deleted, and that frees rows rather than bytes.** `DELETE /git/:repoId` removes
every row keyed by the id — packs, pack files, the protection override, the lines-of-code memos — in
one transaction, so qits-projects deleting a repository no longer leaves a bare behind at an id
nobody holds. The pack blobs stay: the store is content-addressed and shared, nothing counts
references to a blob, so they are orphaned exactly as a repack's are. A census sweep is what will
collect both, and there is not one yet.

## Building

**Clone AND initialise the submodule.** `verify` runs `package` on its way to failsafe, and
`package` is where Quinoa builds the client — an uninitialised `service/src/main/webui` is an empty
directory, which Quinoa stops on (`No package.json found in Web UI directory`).

```
git submodule update --init service/src/main/webui
(cd service/src/main/webui && npm ci)
./mvnw -B verify
```

`mvn test` alone needs neither node nor the submodule: Quinoa is disabled by default in test mode.
That is also why no `@QuarkusTest` can prove anything about what `/` serves — only the packaged
artifact can, and this service needs both its databases to boot, so those probes ride a platform
bootstrap. After the root-path flip the list to run is: `/` → 200 HTML with `<base href="/">`, a
deep link → `index.html`, `/githost/api/nope` and `/githost/q/nope` → 404 never HTML, and a mistyped
`/git/…` or `/bootstrap-git/…` → 404, which is the half the absolute `ignored-path-prefixes` list
now carries. `/githost/q/health/ready` → UP.

`npm ci` needs the platform's npm registries (localhost:8081 for the `@qits` scope, localhost:8082
for the npmjs cache — the client's committed `.npmrc`); Quinoa itself reuses the `node_modules` it
finds and runs the host's node.

Otherwise the build needs no docker: the suite drives the real `git` CLI against the in-process
routes, and both databases are real PostgreSQL binaries resolved as Maven artifacts and spawned as a
child process (zonky). `qits-eventstream` resolves from the platform Maven repository; everything
else is Maven Central or this reactor. `qits-blobstore` is pinned to
`1.0.0-pgblobs-SNAPSHOT` while the PostgreSQL blob store is on its branch, so a build here needs
that branch installed (`./mvnw install` in `libs/qits-blobstore`) until it releases.

On the deployment host add `-Dquarkus.http.test-port=0`: Quarkus' default test port 8081 is the
platform's npm registry there, and the whole suite otherwise dies with `Port already bound: 8081`,
which reads like a code failure and is not one. (`src/test/resources/application.properties` already
sets it; the flag is for anything that overrides that file.)

`service` compiles to a GraalVM native image, which is what a deployment runs:

```
sdk env && ./mvnw -B verify -Dnative
```

`.sdkmanrc` names `25.0.2-graalce`, so this needs no container. Without a GraalVM on the path Quarkus
falls back to a 1.8 GB Mandrel image over docker and stays green either way — recognise the fallback
by the image pull. The four `--initialize-at-run-time` flags in `application.properties` are JGit's,
and `bus/EventWireReflection` is the event vocabulary's: JGit is not a Quarkus extension and
`CanonicalJson` builds its own `ObjectMapper`, so nothing registers either on their behalf and both
failures land at runtime in the binary while every `@QuarkusTest` stays green.
