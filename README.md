# qits-githost-service

The platform's git smart-HTTP host, deployed as the `qits-githost` application. Every repository
qits serves is here: workspace containers clone and push over HTTP, qits-ci reads a pipeline config
out of one file, qits-projects folds and tags release requests through its git primitives, and a
push announces itself to the platform as a durable domain event.

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
| `service` | The deployable: the Vert.x routes, the REST API, the Angular client (`src/main/webui`, the `qits-githost-frontend` submodule, served by Quinoa), the two port adapters over `qits-blobstore`, the schema, and the publisher. |

## Addresses

Four surfaces, one port, one host. **`githost.<env>.<domain>` is this service's own host**, and it
serves the client at its root. `/githost` stays this service's machine segment and `/git` the wire
protocol's; the edge path-routes both on every host, so nothing that names them has to move.

| Address | What it is |
|---|---|
| `/git/**` | The git wire protocol. Plain Vert.x routes, the prefix a literal in `GitHostRoutes` — git treats the base as opaque, so no config key can move it. |
| `/githost/api/**` | The REST API (`quarkus.rest.path`), read by the client and by nothing that speaks git. |
| `/` | The Angular client, built and served by Quinoa out of `service/src/main/webui` (the `qits-githost-frontend` submodule). The old bare-`/githost` trailing-slash wart (upstream quinoa #960) went with the move to the root. |
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

**The id-addressed CONTENT reads take one further list, and nothing else does.**
`qits.githost.content-readers` names roles admitted to `GET /git/<repoId>/blob/…` and `…/tree/…`
beside the storage client — the platform's deployer reads a released repository's
`.config/qits/deployments.yml` and is handed a storage id and no name, because a `SoftwareRelease`
carries the repository as its id. Clone, push, the lifecycle verbs and `GET /git` are untouched by
it and stay closed to the storage client alone. Unset — the shipped default — is the behaviour that
predates the key.

| Route | What it does |
|---|---|
| `GET /git/:projectId/:repoName/info/refs?service=…` | The ref advertisement, name-addressed. |
| `POST /git/:projectId/:repoName/git-upload-pack` | Fetch / clone. |
| `POST /git/:projectId/:repoName/git-receive-pack` | Push. |
| `GET /git/:projectId/:repoName/blob/:rev/<path>` | The raw bytes at that path in that revision. `Git-Commit-Sha` names the resolved commit. |
| `GET /git/:projectId/:repoName/tree/:rev[/<path>]` | `{"entries":[{"name","type"}]}` for the directory there. `type` is `tree`, `blob` or `commit`; a `commit` is a submodule gitlink and carries `sha` (the pinned commit) and `mode` (`"160000"`) as well. |
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

### The git primitives

Beside the browser's reads sit the **write primitives** — generic git operations a domain service
composes, all of them **in-core against the bare** (JGit, no worktree, no clone, no checkout) and all
of them **machine-only** (`qits:system`; a browser session's `qits:admin` is refused). They speak
refs, shas and paths and know nothing about what they are being used for.

| Route | What it does |
|---|---|
| `POST /githost/api/repositories/{repoId}/merges` | Octopus-merges N sources into a target branch ref. |
| `POST /githost/api/repositories/{repoId}/tags` | Creates an annotated tag at a sha. **Refuses an existing tag.** |
| `POST /githost/api/repositories/{repoId}/commits` | Writes a map of path → content as one commit on a branch ref. |
| `DELETE /githost/api/repositories/{repoId}/branches/{name}` | Deletes a branch ref. Never the default branch. |

```
POST /githost/api/repositories/<repoId>/merges
{"target": "refs/heads/release/17",
 "sources": ["refs/heads/main", "feature/x", "refs/tags/2026.901.1", "<sha>"],
 "message": "…",                                  // optional
 "author": {"name": "…", "email": "…"}}           // optional

200 {"target": "refs/heads/release/17", "sha": "<commit>", "outcome": "merged",
     "parents": ["…", "…"], "skipped": ["refs/heads/main"]}
409 {"error": "merge-conflict", "target": "…",
     "conflicts": [{"path": "pom.xml", "head": "feature/x", "headSha": "…", "reason": "content"}]}
```

**The target's own tip is the first head**, which is what makes this git's octopus rather than an
invention: `git merge A B C` folds onto `HEAD` and writes four parents, and the target ref plays
`HEAD` here. A head another head already contains is dropped — git's own "Already up to date" — and
the two properties that matter fall out of that one rule:

- **nothing changed since the last merge → no new commit.** Every source is then a parent of the
  target's tip, so every source drops out and the target is the only head left: `outcome`
  `unchanged`, the same sha as last time. Re-merging is free and leaves no garbage.
- **one effective head → no empty octopus.** The ref is created at it or fast-forwarded onto it
  (`outcome` `fast-forward`); a one-parent "merge" is never written.

`outcome` is therefore one of `merged`, `fast-forward` and `unchanged`. **A conflict moves no ref**
and is reported, never resolved: the paths, and for each the head that was being folded in when it
broke, spelled as the caller spelled it.

```
POST /githost/api/repositories/<repoId>/tags
{"name": "2026.903.120000",          // or the full refs/tags/… ref
 "sha": "refs/heads/release/17",     // a ref, tag or sha naming the commit to tag
 "message": "…",                     // optional, defaults to the tag's own name
 "author": {"name": "…", "email": "…"}}

201 {"tag": "refs/tags/2026.903.120000", "sha": "<tag object>", "object": "<commit>"}
409 {"error": "tag-exists", "tag": "refs/tags/2026.903.120000", "sha": "<what the ref says>"}
```

**The 409 is the contract**, not a nicety: it is the platform's version-uniqueness guarantee, and it
replaces the atomic branch-and-tag push the workspaces release door relied on. A caller stamps a
version, asks for the tag, and `tag-exists` means "somebody already released that — stamp another
one", distinguishable from every other way the request could fail. The race is refused the same way:
the ref is created with an expected-old of zero, so two callers asking for one name produce one tag
and one refusal. Tags are always annotated.

```
POST /githost/api/repositories/<repoId>/commits
{"ref": "refs/heads/release/17",
 "message": "bump the manifests",
 "files": {"pom.xml": "…", "web/package.json": "…"},   // path -> UTF-8 content
 "deletePaths": ["old/thing.txt"],                     // optional
 "author": {"name": "…", "email": "…"}}                // optional

200 {"ref": "…", "sha": "<commit>", "parent": "<old tip>", "outcome": "committed" | "unchanged"}
409 {"error": "ref-moved", …}     // the branch moved under the caller
```

The content is the caller's — this host writes it and reads none of it, which is why the primitive is
a map of paths and not an operation on manifests. **An edit that produces the tip's own tree writes
nothing** (`unchanged`, ref untouched), so a retried bump after a timeout is free rather than a
second empty commit. The ref moves as a compare-and-swap against the tip the request read.

```
DELETE /githost/api/repositories/<repoId>/branches/release/17      // 204
DELETE /githost/api/repositories/<repoId>/branches/main            // 409 {"error":"protected-branch"}
```

The name is the path tail, so a slashy branch needs no encoding dance, and `refs/heads/…` is accepted
as well. **The default branch is refused unconditionally** — `ProtectedRefHook` guards the same ref
on the push door, and this door would otherwise be a hole in that seatbelt shaped like an HTTP call.
Unconditionally rather than under that hook's `protect-default-branch` switch: the switch ships off
because this host serves its own redeploy pushes, an argument about pushes that this door is not.

**These writes fire no `post-receive` and publish no events.** That inverts the property the DFS
storage was built for — receive-pack as the only writer — deliberately: the caller is a domain
service already narrating what it is doing, and an `SCMPublishCommit` per intermediate merge would
announce steps nobody outside it can act on.

### The client

`service/src/main/webui` is the `qits-githost-frontend` submodule, an Angular 21 SPA that Quinoa builds
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
| `QITS_GIT_AUTHOR_NAME` / `QITS_GIT_AUTHOR_EMAIL` | Who a commit the git primitives manufacture belongs to. The platform's key pair — qits-workspaces reads the same one — defaulting to `qits <qits@local>`. |

There is no variable for where the bytes go any more. `qits.artifacts.blobs-datasource=githost`
points the blob store at this service's own database, and a deployment has no reason to move it.

Push options, all read inside the pack protocol rather than as headers (qits-gateway strips the
whole `X-Qits-` prefix, so a header would behave differently through the front door):

- `-o qits.release` — an integrate-produced release. Fast-forward only.
- `-o qits.token=<value>` — push the protected branch anyway, if the value matches.
- `-o qits.no-ci` — not a bypass. It rides through to `SCMPublishCommit.suppressCi`.

## Deployment

A release builds `docker/Dockerfile` — a Mandrel builder stage that native-compiles `service`, a
`ubi-minimal` runtime stage that carries only the binary — and pushes it as
`qits/qits-githost:<version>` (`.config/qits/ci-event-release.yml`, riding `SCMRelease`). **Nothing
builds a push any more**: per-push CI is retired platform-wide, and this repository's other pipeline,
`.config/qits/ci-event-release-request.yml`, runs the same build — minus the push — plus `mvn
verify` and the userflow publish against a release request's fold, `release/<id>`. The build half is
gating; the userflow half declares `gating: false`, so a red verify shows red without holding the
fold. Both image builds run `--network host` with `--build-arg QITS_MAVEN_REPOSITORY_URL=…`, because
`qits-eventstream` exists only in the platform's own Maven repository and a docker build reaches no
other address for it.

**Each pipeline is one step with two halves**, and the split is not cosmetic: the client depends on
`@qits/ui-components`, which lives only on the platform's own npm registry, and a docker `RUN` can
reach that registry by no address at all. So the step container builds the bundle first, and the
image build packages one that already exists — its Quinoa install/ci/build commands are neutered to
`--version`, and a missing bundle is a red build at a `test -f` guard rather than an image that
boots and serves `/` as a 404.

`.config/qits/deployments.yml` is the deploy answer: **an environment service** — every tier
runs its own git host, and a released version is deployed into a tier by a Deployment Request, never
by a branch — with `resources: postgresql:db, postgresql:eventstream:qits_githost_eventstream` and the health gate
at `/githost/q/health/ready`. Those two resource **names** are load-bearing: they are what makes
`QITS_RESOURCE_DB_*` and `QITS_RESOURCE_EVENTSTREAM_*` exist, and neither triple has a default, so a
missing one kills the boot at Flyway rather than opening a store nobody meant. **There is no blobs
volume any more**: the container is stateless except for its two databases, so a deployment still
mounting one is carrying dead bytes. Deploy this service alone: replacing it blinks the host every
other repository's build clones from.

## User stories

Every integration test in `service` is a **userflow**: a `@UserStory` that emits its own
documentation under `service/target/userstories/<category>/<slug>/` — the steps, the command
transcripts, the files a story wrote, and a **network diagram** — which the non-gating second step
of `ci-event-release-request.yml` publishes as the `@userflows/qits-githost` docs site, once per
release-request fold. The proof and the documentation are the same
artifact, so neither can go stale without the build going red.

**Three categories, seven stories.**

| category | story | what it proves |
| --- | --- | --- |
| `authentication` | On start, the git host fetches the platform's signing keys | the shipped `quarkus.oidc.*` block against a real listener — the one thing no `@QuarkusTest` here can exercise, since `%test` disables the tenant outright |
| `authentication` | A stranger's token never opens the git host | an unknown key and a wrong audience are both refused at the door |
| `git` | A developer clones a repository | the PUBLIC clone url serves a real `git clone`, and the clone's HEAD is the commit the origin was given |
| `git` | A push lands new history | receive-pack moved the ref, and the origin now advertises AND serves what the push carried |
| `git` | A pull fetches what a teammate pushed | two working copies, two initiators, one repository |
| `git` | A pipeline reads a file without cloning | the content routes answer a directory and a file in two plain GETs |
| `browse` | A reader opens a file in the code browser | the SPA's four reads, and that this plane asks qits-projects nothing |

`api/TokenValidationBootstrapIT` owns the first category; the rest live under
`githost/stories/{git,browse}/` with their support in `githost/stories/support/`.

**One profile, one launched process.** Every story class names
`TokenValidationBootstrapIT.PackagedWithMockIdp`, so failsafe launches the packaged artifact once
for the whole IT phase. That is not a speed optimisation: the git stories' only possible network tap
is the server's own access log, and one process means one log to attribute.

**Two mocks stand in for the platform.** `idp.MockIdp` serves the JWKS and mints every bearer a
story presents — validation is real, and a token minted for another audience is refused by the same
code path a deployment runs. A plain `MockService` plays **qits-projects' name resolver**, which is
what makes `/git/:projectId/:repoName` serve at all; without `qits.projects.name-resolver-url` the
public scheme 404s and not one git story could clone anything. Both mocks' recordings are registered
as `NetworkCapture` sources, so the far half of every diagram is observed on the far side rather
than claimed here.

**The diagram is observed, never narrated.** Three taps feed it and no story method draws an edge:

- the framework's `NetworkTaps.restAssured` for what `TokenValidationBootstrapIT` sends (this
  repository's hand-copied `StoryNetworkFilter` was deleted when the tap shipped);
- `stories/support/StoryAccessLog`, which parses `quarkus.http.access-log`'s `%m %U %s` — `%U`
  carries the query, which is what distinguishes `?service=git-upload-pack` from
  `?service=git-receive-pack`;
- the two mock recordings above.

`StoryAccessLog` does two things a copy of the sibling services' access-log tap does not. It decides
the **kind per line**: the three smart-HTTP shapes are `git` (a negotiated protocol exchange, whose
transport happening to be HTTP says nothing about what it means) and everything else — the content
routes, the browser plane — is `http`. And it stamps the **actor per line as it harvests**, rather
than reading one initiator at drain time, which is what lets the pull story draw the teammate's push
and the developer's pull as two different people.

**A story drives the public scheme; the fixture drives the storage one.** `stories/support/
StoryOrigin` provisions and seeds through `/git/:repoId` — it is playing qits-projects' own client,
which is the only caller with a legitimate reason to speak storage ids — and `StoryAccessLog` drops
every request on that scheme, because provisioning a fixture is setup rather than a walk anybody
takes. The two never collide, for the same reason the router can tell them apart: the public scheme
carries one more path segment. The fixture also **deletes before it creates**, since the storage ids
are fixed literals and the IT database is not wiped between builds — otherwise "a push lands NEW
history" would be pushing history that was already there.

**What is asserted, and what is deliberately not.** `assertEdge` pins every arrow;
`assertOnlyEdgesFrom` pins the actor *set*, which is the honest claim for a git flow because the
request COUNT of a clone or a push belongs to the client (protocol v2 splits a fetch into two POSTs
that dedupe into one edge; v0 sends one). `assertEdgeCount` appears exactly twice — the file read
and the code browser — where the number of requests is a property of the routes rather than of the
tool. `assertNotLeaked` runs in every git story: the bearer rides on `git -c http.extraHeader` and
is on four command lines per story, and `Commands.redact` is what keeps it out of the published
bytes.

Run them alone with:

```
./mvnw -B -pl service -am -DskipITs=false -Dtest=SKIPNONE \
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='*IT' verify
```

Both `git` and `curl` must be on `PATH`; a machine without either **skips** the affected classes
(`@EnabledIf` on `StoryTools`) rather than failing, because a skipped story emits nothing at all,
which is the honest answer for "this machine has no git".

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
routes and — in the userflow ITs — the real `git` and `curl` against the packaged artifact on a
socket the test JVM is not on, and both databases are real PostgreSQL binaries resolved as Maven
artifacts and spawned as a child process (zonky). A machine missing either tool skips the story
classes that need it and stays green. `qits-eventstream` resolves from the platform Maven repository; everything
else is Maven Central or this reactor. `qits-blobstore` is pinned to
`1.0.0-pgblobs-SNAPSHOT` while the PostgreSQL blob store is on its branch, so a build here needs
that branch installed (`./mvnw install` in `components/qits-registries/qits-registries-javalib`) until it releases.

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
