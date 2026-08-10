# qits-githost

The platform's git smart-HTTP host. Every repository qits serves is here: workspace containers
clone and push over HTTP, qits-ci reads a pipeline config out of one file, qits-workspaces releases
through it, and a push announces itself to the platform as a durable domain event.

It moved out of `qits-platform-artifacts` with its history (byte-plane-split-plan.md phase 3). A git
repository is not an artifact — it only shared the blob store's storage layout — and every consumer
is an env service, so an env-scoped git host is the consistent shape.

## Modules

| Module | What it is |
|---|---|
| `git-storage` | The storage engine: a JGit `DfsRepository` whose packs, pack indexes and reftables are content-addressed blobs. A plain library jar with ONE compile dependency (JGit) and two ports it declares and does not implement. |
| `githost-events` | The event vocabulary. Depends on `qits-eventstream` and nothing else. **This is the jar a consumer depends on.** |
| `service` | The deployable: the Vert.x routes, the two port adapters over `qits-blobstore`, the schema, and the publisher. |

## Routes

Everything is a plain Vert.x route at `/git`, spelled as a literal in `GitHostRoutes` — git treats
the base as opaque, so no config key can move it. There is no JAX-RS surface, no OpenAPI document
and no UI.

| Route | What it does |
|---|---|
| `GET /git` | `{"repositories": ["<repoId>", …]}` — every repository this host serves, sorted. |
| `PUT /git/:repoId` | Create, idempotently. Body `{"defaultBranch": "main"}`. 201 created, 200 already there. |
| `GET /git/:repoId` | `{"repoId", "defaultBranch"}`, or 404. |
| `HEAD /git/:repoId` | The same existence question, no body. |
| `GET /git/:repoId/info/refs?service=git-(upload\|receive)-pack` | The ref advertisement. |
| `POST /git/:repoId/git-upload-pack` | Fetch / clone. |
| `POST /git/:repoId/git-receive-pack` | Push. |
| `GET /git/:repoId/blob/:rev/<path>` | The raw bytes at that path in that revision. `Git-Commit-Sha` names the resolved commit. |
| `GET /git/:repoId/tree/:rev[/<path>]` | `{"entries":[{"name","type"}]}` for the directory there. |
| `GET /git/:projectId/:repoName/info/refs?service=…` | The name-addressed scheme. |
| `POST /git/:projectId/:repoName/git-upload-pack` | Fetch, name-addressed. |
| `POST /git/:projectId/:repoName/git-receive-pack` | Push, name-addressed. |
| `GET /git/q/health/ready` | Readiness, for qits-cd's health gate. |

**The prefix changed.** It was `/artifacts/git` while the host lived inside qits-artifacts; standing
alone it drops the borrowed segment. qits-gateway routes verbatim by prefix, so `/git` is this
service's own entry, and every client that names the old path has to be moved with the cutover.

The name-addressed scheme resolves `(projectId, repoName)` through qits-projects
(`qits.projects.name-resolver-url`) and is what makes a committed relative submodule url
(`../<name>.git`) work. Unset, that scheme answers 404 and the id-addressed one keeps serving.

## Events

A push publishes through `QitsEventBus` — the qits-eventstream outbox, so a consumer that was down
while the push landed reads the event back. This replaces the post-receive HTTP fan-out, which
retried in memory for about three minutes and then logged the loss.

Depend on `eu.wohlben.qits:qits-githost-events` for the vocabulary. Wire name = simple class name.

| Event | When | Payload |
|---|---|---|
| `SCMPublishCommit` | per successfully updated branch ref | `repoId`, `branch`, `oldSha`, `sha`, `parents[]`, `authorName`, `authorEmail`, `authoredAt`, `committedAt`, `message`, `suppressCi`, `receivedAt` |
| `SCMPublishTag` | per created or updated tag ref | `repoId`, `tagName`, `sha`, `targetSha`, `taggerName`, `taggerEmail`, `message`, `annotated`, `receivedAt` |
| `SCMDeleteBranch` | per deleted branch ref | `repoId`, `branch`, `sha` (the old tip), `receivedAt` |
| `SCMDeleteTag` | per deleted tag ref | `repoId`, `tagName`, `sha` (the old tip), `receivedAt` |

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

qits-eventstream propagates a cause with a pair of JAX-RS filters. This service has no JAX-RS
surface, so `GitHostRoutes.causationOf` reads the header itself and wraps the post-receive
announcement in `CausationScope.with(...)`. Blank and malformed both read as absent: causation is
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

The library jars ship their own defaults at ordinal 100 (`qits-blobstore`: the blob directory;
`qits-eventstream`: the bus url, the outbox datasource and lineage, the retry budget). What this
service owns is in `service/src/main/resources/application.properties`.

| Key / variable | Meaning |
|---|---|
| `QITS_RESOURCE_DB_URL` / `_USERNAME` / `_PASSWORD` | This service's PostgreSQL database (pack catalog, protection overrides). No defaults — an unset variable fails the boot. |
| `QITS_RESOURCE_EVENTSTREAM_URL` / `_USERNAME` / `_PASSWORD` | The outbox's own database, from the qits-eventstream jar. |
| `QITS_EVENTS_URL` | Where qits-events answers. Scheme, host and port, no path. |
| `QITS_PROJECTS_NAME_RESOLVER_URL` | Where qits-projects resolves a repository name. No default; unset means the name-addressed scheme 404s. |
| `QITS_REPOSITORIES_GIT_PROTECT_DEFAULT_BRANCH` | The default branch's seatbelt. Ships `false`. |
| `QITS_REPOSITORIES_GIT_PUSH_TOKEN` | What `-o qits.token=<value>` must match. No default: unset means no token matches. |
| `QITS_REPOSITORIES_GIT_MAX_PACK_SIZE` | The largest push this host accepts. Ships `64M`. |
| `QITS_ARTIFACTS_BLOBS_DIR` | Where packs, pack indexes and reftables live on disk. |

Push options, all read inside the pack protocol rather than as headers (qits-gateway strips the
whole `X-Qits-` prefix, so a header would behave differently through the front door):

- `-o qits.release` — an integrate-produced release. Fast-forward only.
- `-o qits.token=<value>` — push the protected branch anyway, if the value matches.
- `-o qits.no-ci` — not a bypass. It rides through to `SCMPublishCommit.suppressCi`.

## Deployment

A push builds `docker/Dockerfile` — a Mandrel builder stage that native-compiles `service`, a
`ubi-minimal` runtime stage that carries only the binary — and pushes it as
`qits/qits-githost:<sha>`; a release rebuilds the same content under the released version
(`.config/qits/ci-post-receive.yml` and `.config/qits/ci-event-release.yml`). Both builds run
`--network qits-net` with `--build-arg QITS_MAVEN_REPOSITORY_URL=…`, because `qits-eventstream`
exists only in the platform's own Maven repository and a docker build reaches no other address for
it. `.config/qits/deployments.yml` is the deploy answer: **an environment service** — every tier
runs its own git host, and a green build deploys into whichever tier listens to the built branch —
with `resources: postgresql:db, postgresql:eventstream:qits_githost_eventstream` and the health gate
at `/git/q/health/ready`. Those two resource **names** are load-bearing: they are what makes
`QITS_RESOURCE_DB_*` and `QITS_RESOURCE_EVENTSTREAM_*` exist, and neither triple has a default, so a
missing one kills the boot at Flyway rather than opening a store nobody meant. The blob directory
(`QITS_ARTIFACTS_BLOBS_DIR`) and the volume behind it are run-args, written by the bootstrap CLI —
the deployment grammar has no key for a mount. Deploy this service alone: replacing it blinks the
host every other repository's build clones from.

## Storage

A repository has no directory anywhere. Its packs, pack indexes and reftables are blobs in this
service's own content-addressed store; the pack list is rows in `git_pack` / `git_pack_file`. So
receive-pack is the only writer, and no ref moves without the post-receive hook firing.

**The platform does not garbage collect git**, deliberately. The blob store has no delete on this
path, so `DfsGarbageCollector` does not reclaim, it duplicates — measured once on the platform's
largest repository, 22 packs and 7.8 MB became 2 packs and 15 MB. The accepted cost instead is about
three blobs and three rows per push.

## Building

```
./mvnw -B verify
```

Needs no docker and no network: the suite drives the real `git` CLI against the in-process routes,
and both databases are real PostgreSQL binaries resolved as Maven artifacts and spawned as a child
process (zonky). `qits-eventstream` resolves from the platform Maven repository; everything else is
Maven Central or this reactor.

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
