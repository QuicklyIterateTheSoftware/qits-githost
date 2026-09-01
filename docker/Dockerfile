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
# THOSE TWO DATABASES ARE THE WHOLE OF THIS CONTAINER'S STATE. Packs, pack indexes and reftables are
# chunk rows beside the git_pack / git_pack_file rows that index them (V2__blob_tables.sql, and
# qits.artifacts.blobs-datasource=githost), so there is NO blobs directory and NO volume to mount. A
# restart loses nothing: every repository this host serves lives in the database.
#
#   docker run \
#     -e QITS_RESOURCE_DB_URL=jdbc:postgresql://some-postgres:5432/qits_githost \
#     -e QITS_RESOURCE_DB_USERNAME=... -e QITS_RESOURCE_DB_PASSWORD=... \
#     -e QITS_RESOURCE_EVENTSTREAM_URL=jdbc:postgresql://some-postgres:5432/qits_githost_eventstream \
#     -e QITS_RESOURCE_EVENTSTREAM_USERNAME=... -e QITS_RESOURCE_EVENTSTREAM_PASSWORD=... \
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
# `mirror.dev.localhost:8080/quay/…` is the platform's pull-through mirror of quay.io: the first
# build fetches the image from upstream and caches it, every later build is served from local disk.
# The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay
# moves it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
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
#   address — not the qits-net alias, not registry.dev.localhost:8080, not host.docker.internal. So
#   the install cannot happen here, and one that "worked" would have resolved the @qits scope
#   against npmjs.org, where that name does not exist and never will.
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
ARG QITS_MAVEN_REPOSITORY_URL=http://registry.dev.localhost:8080/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL
#
# --- and where Maven Central is mirrored, when it is ----------------------------------------------
# The pipeline passes the platform's pull-through (qits-platform-mirror's central proxy, the same
# authority the FROM lines above pull through); EMPTY — the default — means direct Maven Central.
# `.qits-maven-settings.xml` activates its central-proxy profile only on a non-empty value, so a
# bootstrap or hand build that passes nothing resolves exactly as it always did.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL
#
# --- and how Maven authenticates, once the registry stops answering anonymously -------------------
# The two secret mounts put the CI run's commissioned client into THIS `RUN`'s environment and
# nowhere else — not a layer, not the image history. That is why they are not build args: this image
# is pullable, and a build arg is recorded where anyone who pulls it can read it.
# `.qits-maven-settings.xml` reads the two variables as the `qits-maven-network` server.
#
# Absent secrets leave both variables unset, which is the state every build is in today and the
# state a host-side `docker build` is in permanently: Maven sends credentials only when a repository
# answers 401, and the registry does not yet.
RUN --mount=type=secret,id=qits-client-id,env=QITS_MAVEN_AUTH_USR \
    --mount=type=secret,id=qits-client-secret,env=QITS_MAVEN_AUTH_PSW \
    d=service/src/main/webui/dist/qits-spa-githost/browser; \
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
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# NOTHING IS INSTALLED HERE, and `git` least of all. This service speaks the git wire protocol
# through JGit, in process; it shells out to nothing. A `git` binary on a host whose only job is to
# serve pack files to anonymous callers would be a tool for somebody else to reach for.
#
# `quarkus.package.output-name` names the binary `qits-githost` (no -runner suffix), so the process
# is identifiable in `ps` / `docker top`.
COPY --from=build --chown=1001:root /src/service/target/qits-githost /work/qits-githost
RUN chmod +x /work/qits-githost

# NO /data AND NO VOLUME. This image used to own a mount point for the pack files; pack bytes are
# PostgreSQL rows now, so the container writes nothing it needs to keep and there is nothing to
# mount. A deployment that still mounts the old volume is carrying dead bytes.
#
# Nothing is named here either: no QUARKUS_DATASOURCE_*. The two databases belong to the deployment,
# and this image refuses to start until a deployment says where they are.

# One port for all of it — /git/** (raw Vert.x), /githost/api/** (JAX-RS), /githost/** (the SPA's
# static files) and /githost/q/** (health) are four route stacks in one process, not four listeners.
EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the gateway, not a peer on qits-net.
ENTRYPOINT ["/work/qits-githost", "-Dquarkus.http.host=0.0.0.0"]
