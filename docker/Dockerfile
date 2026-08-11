# qits-githost as a GraalVM-native container IMAGE — the same shape as qits-artifacts',
# qits-platform-dns' and qits-gateway's docker/Dockerfile: a Mandrel builder stage compiles the
# binary, a minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw verify -Dnative` produces
# `service/target/qits-githost` locally with no container involved. What this adds is the image
# around it — and a way for a machine with no GraalVM (a CI runner, a laptop on a plain OpenJDK) to
# get a binary at all, since the builder stage brings its own native-image.
#
# Build from the repo root, on the network where the platform's Maven repository answers:
#
#     git submodule update --init service/src/main/webui
#     (cd service/src/main/webui && npm ci && npm run build)
#     docker build --network host \
#       --build-arg QITS_MAVEN_REPOSITORY_URL="http://$QITS_REGISTRY/artifacts/maven/maven" \
#       -t qits/githost:latest -f docker/Dockerfile .
#
# THE FIRST TWO LINES ARE NOT A CONVENIENCE. This image carries the git host's client
# (service/src/main/webui = the qits-spa-githost submodule), and the builder stage below packages a
# bundle that was ALREADY BUILT rather than building one. See the Quinoa flags on the mvnw line for
# why that stage cannot build it.
#
# BOTH FLAGS ARE REQUIRED and neither has a workable default. This reactor's `service` module depends
# on `qits-eventstream`, a jar published to the platform's own Maven repository and available from
# nowhere else — not Maven Central, and not the builder container's empty ~/.m2. A RUN step reaches
# the public internet and nothing else, so without `--network` there is no address that answers at
# all. See .config/qits/ci-post-receive.yml, which passes exactly this pair.
#
# --- this image does NOT run with an empty environment, and that is not a packaging bug ------------
# `docker run --rm qits/githost:latest` dies before the HTTP listener, at Flyway, because this
# service ships BOTH its datasources as the platform's generic resource contract and nothing resolves
# either:
#
#   quarkus.datasource.githost.jdbc.url=${QITS_RESOURCE_DB_URL}          (the pack catalog)
#   quarkus.datasource.eventstream.jdbc.url=${QITS_RESOURCE_EVENTSTREAM_URL}   (the outbox)
#
# THAT IS THE DESIGN AND A FALLBACK IS NOT A FIX. A git host that came up healthy on a store nobody
# meant would advertise zero refs to every clone and report no outage doing it. The refusal is what
# turns that into a visible failure.
#
# ON THE PLATFORM NOBODY SETS THESE BY HAND. This repository's .config/qits/deployments.yml carries
# `resources: postgresql:db, postgresql:eventstream:qits_githost_eventstream`; qits-deployments
# creates a role and a database for each on the tier's postgres before the successor container starts
# and injects all six variables.
#
# One more path has no safe default, and it is the one that holds the bytes:
#
#   QITS_ARTIFACTS_BLOBS_DIR   Where packs, pack indexes and reftables live. A repository has no
#                              directory anywhere — its objects are content-addressed blobs here, and
#                              the rows in the database above are their only index. So the two travel
#                              together, and blobs whose index did not survive are not data, they are
#                              orphans. The shipped default is ${user.home}/.qits/data/githost/blobs,
#                              which inside this image resolves to nothing usable: UID 1001 has no
#                              /etc/passwd entry in ubi-minimal and a native image reads user.home
#                              through getpwuid, so setting HOME does not fix it.
#
# So a deployment mounts a volume at /data (which this image owns, see the runtime stage) and names
# all of it:
#
#   docker run -v qits-githost-data:/data \
#     -e QITS_RESOURCE_DB_URL=jdbc:postgresql://some-postgres:5432/qits_githost \
#     -e QITS_RESOURCE_DB_USERNAME=... -e QITS_RESOURCE_DB_PASSWORD=... \
#     -e QITS_RESOURCE_EVENTSTREAM_URL=jdbc:postgresql://some-postgres:5432/qits_githost_eventstream \
#     -e QITS_RESOURCE_EVENTSTREAM_USERNAME=... -e QITS_RESOURCE_EVENTSTREAM_PASSWORD=... \
#     -e QITS_ARTIFACTS_BLOBS_DIR=/data/githost/blobs \
#     -e QITS_PROJECTS_NAME_RESOLVER_URL=http://<env>-qits-projects:8080/projects/api/projects \
#     qits/githost:latest
#
# The last one is the name-addressed scheme's resolver and is the only entry here that is optional:
# unset, `/git/:projectId/:repoName` answers 404 and the id-addressed routes keep serving. See
# README.md for the whole configuration surface.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `localhost:8082/quay/…` is the platform's pull-through mirror of quay.io: the first build fetches
# the image from upstream and caches it, every later build is served from local disk. The tag is
# upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay moves it.
FROM localhost:8082/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `service` depends on `git-storage` and `githost-events`,
# 1.0.0-SNAPSHOTs this repo publishes nowhere, so a builder container with an empty ~/.m2 has nowhere
# else to get them — the sources have to be in the context. Copying module by module would mean
# editing this file every time one is added, and forgetting to would fail the build with an
# unresolved dependency rather than anything that names the real cause. `.dockerignore` is what keeps
# this cheap, and it also stops a stale target/ shadowing what this stage compiles.
COPY . .

# -Dnative flips quarkus.native.enabled (the `native` profile lives in service/pom.xml — only that
# module is an application). Cap native-image RAM so the build is deterministic on small machines.
#
# `-pl service -am` builds the app and its dependency closure rather than the whole reactor. It is
# not a module list: this repository publishes `githost-events` as a library for consumers, and a
# whole-reactor build would let a compile error in something no image contains block deploying this
# service. The image build should depend on exactly what the image contains.
#
# Tests are skipped because `./mvnw verify` on a clone is their gate (README.md) and re-running them
# here proves the same thing more slowly — and they need real `git` and two spawned postgres child
# processes, none of which this stage is the right place to arrange. Note the native profile also
# flips skipITs to false, so stopping at `package` rather than `verify` is deliberate.
#
# `-s .qits-maven-settings.xml` is what lets the `qits-maven` repository be an http:// URL at all:
# Maven blocks external http repositories by default, and an exact repository-id mirror is the one
# thing that outranks the blocker. The settings file permits that one id and nothing else.
#
# --- the Quinoa flags, and why this stage does NOT build the client -------------------------------
#
# package-manager-install: the Mandrel builder image ships no node at all, so this stage installs its
# own — from nodejs.org, which a docker build can reach. It lives here rather than in
# application.properties on purpose: a developer machine already has node and should keep using its
# own, and only this stage has to be told, so no build silently downloads a toolchain. 22.22.0 is
# the PLATFORM pin. It is deliberately NOT the step container's node (node-docker-base is
# node:24-alpine, and the bundle this image consumes was built there); the two need not agree,
# because nothing here builds a bundle.
#
# The three command overrides are the load-bearing part, and the reason this file deviates from the
# platform's Quinoa recipe (docs/project-setup-quinoa-angular.md in the qits monorepo, which has the
# builder run the client's real install and build):
#
#   service/src/main/webui is qits-spa-githost, and it depends on @qits/ui-components. That package
#   exists ONLY on the platform's own npm registry, which a RUN step in a docker build reaches by NO
#   address — not the qits-net alias, not localhost:8081, not host.docker.internal. So the install
#   cannot happen here, and one that "worked" would have resolved the @qits scope against npmjs.org,
#   where that name does not exist and never will.
#
# The bundle is therefore built BEFORE this image, by the step in .config/qits/ci-post-receive.yml
# and .config/qits/ci-event-release.yml, and arrives in the build context as
# service/src/main/webui/dist/ (see .dockerignore, where that path is the one client output kept).
# `--version` is the null command: it proves the package manager is really there and does nothing
# else.
#
# THE `test -f` GUARD IS WHAT STOPS THIS FAILING QUIETLY. With no bundle and no package.json Quinoa
# disables itself with a warning, and this stage would stay GREEN while shipping no client — a
# service that boots, answers /githost/ with a 404 and looks deployed. A forgotten submodule init or
# a forgotten `npm run build` is now a red build, stopped before the multi-minute native compile
# rather than found in production.
#
# --- and why the bundle is copied onto itself first -----------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename a
# directory that still lives in a LOWER image layer. It answers EXDEV, the JDK falls back to its
# cross-file-store path, and that path refuses a non-empty directory — the build dies seconds in with
# `DirectoryNotEmptyException: …/dist/qits-spa-githost/browser`, which names the source and explains
# nothing. A builder stage that *creates* dist itself is immune by accident; ours arrives by COPY.
#
# One `cp` re-materialises it in the layer that is about to move it. It has to be in THIS `RUN` and
# not a tidier one of its own — a layer boundary is exactly what it is undoing.
ARG QITS_MAVEN_REPOSITORY_URL=http://localhost:8081/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL
RUN d=service/src/main/webui/dist/qits-spa-githost/browser; \
    test -f "$d/index.html" || { \
      echo "No built client at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version

# --- runtime --------------------------------------------------------------------------------------
# `localhost:8082/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM localhost:8082/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# NOTHING IS INSTALLED HERE, and `git` least of all. This service speaks the git wire protocol
# through JGit, in process; it shells out to nothing. A `git` binary on a host whose only job is to
# serve pack files to anonymous callers would be a tool for somebody else to reach for.
#
# `quarkus.package.output-name` names the binary `qits-githost` (no -runner suffix), so the process
# is identifiable in `ps` / `docker top`.
COPY --from=build --chown=1001:root /src/service/target/qits-githost /work/qits-githost
RUN chmod +x /work/qits-githost

# The mount point for the blobs, created here so it is OWNED. Docker seeds a fresh named volume from
# whatever the image has at the mount path, ownership included; with no such directory it makes one
# root-owned 0755 and the first push dies on an AccessDeniedException instead of anything that names
# the cause. Group-writable and group-root because a ubi image is meant to survive being assigned an
# arbitrary UID with GID 0. (A bind mount still takes its ownership from the host — nothing in an
# image can fix that.)
RUN mkdir -p /data && chown -R 1001:root /data && chmod -R 775 /data

# Nothing is named here: no QUARKUS_DATASOURCE_*, no QITS_ARTIFACTS_BLOBS_DIR. All of it belongs to
# the deployment's storage, and this image refuses to start until a deployment says where it goes.

# One port for all of it — /git/** (raw Vert.x), /githost/api/** (JAX-RS), /githost/** (the SPA's
# static files) and /githost/q/** (health) are four route stacks in one process, not four listeners.
EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the gateway, not a peer on qits-net.
ENTRYPOINT ["/work/qits-githost", "-Dquarkus.http.host=0.0.0.0"]
