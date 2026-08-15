package eu.wohlben.qits.githost;

import eu.wohlben.qits.eventstream.CausationHeader;
import eu.wohlben.qits.eventstream.CausationScope;
import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser.PacketLineOutRefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The in-process git smart-HTTP server, mounted at {@code /git/*} so workspace containers
 * can clone and push over {@code http://<qits-host>:<port>/git/<repoId>}.
 *
 * <p>Implemented as plain Vert.x routes driving JGit's {@link UploadPack}/{@link ReceivePack}
 * directly — deliberately NOT as a servlet. qits used to host this with JGit's {@code GitServlet}
 * on {@code quarkus-undertow}; that dependency broke the SPA the host used to share a process with,
 * and it has stayed out since. Raw routes are also what keeps the wire protocol the whole of what
 * this class does.
 *
 * <p>Every Git route requires either {@code qits:admin} from an authenticated browser session,
 * {@code qits:system} from a machine token, or {@code qits:git:external} from a workstation token.
 * An external token may push only {@code refs/heads/external/*}; repository ids remain identifiers,
 * not capabilities.
 * JGit here speaks the wire protocol and nothing else, and receive-pack is the
 * only writer this host has — a repository has no directory anyone could run git in.
 *
 * <p>One thing a push is checked against: {@link ProtectedRefHook}, the default branch's seatbelt.
 * It is not authentication and is not an authorization system — it guards exactly one ref per repo
 * (the bare's {@code HEAD}) against a reflex {@code git push … main}, and it ships inert. See that
 * class for the mechanism, the two push-option bypasses and why they are options rather than
 * headers.
 *
 * <p>Two addressing schemes, both served:
 *
 * <ul>
 *   <li><b>id-addressed</b> {@code /git/:repoId} — the opaque UUID, handed straight to the
 *       storage (back-compat: already-provisioned containers, metadata, discovery).
 *   <li><b>name-addressed</b> {@code /git/:projectId/:repoName} — a project's
 *       repositories served as siblings, {@code repoName} resolved through the {@link
 *       RepositoryNameResolver} port to a repo id. This is what lets committed relative submodule
 *       urls ({@code ../<name>.git}) resolve natively against a sibling — no {@code
 *       submodule.<name>.url} override. With no resolver on the classpath the scheme answers 404
 *       and only the id-addressed one is served.
 * </ul>
 *
 * <p>The three smart-HTTP endpoints hang off each scheme:
 *
 * <ul>
 *   <li>{@code GET …/info/refs?service=git-(upload|receive)-pack} — the ref advertisement.
 *   <li>{@code POST …/git-upload-pack} — fetch/clone negotiation + packfile.
 *   <li>{@code POST …/git-receive-pack} — push.
 * </ul>
 *
 * <p>Beside those, three lifecycle routes on the id-addressed base only — not served
 * name-addressed, since a name is an alias for an id that has to already exist:
 *
 * <ul>
 *   <li>{@code PUT …/:repoId} {@code {"defaultBranch": "main"}} — create, idempotently: 201 when
 *       this call created the repository, 200 when one was already there.
 *   <li>{@code GET …/:repoId} — {@code {"repoId", "defaultBranch"}} for a repository that exists,
 *       404 otherwise.
 *   <li>{@code HEAD …/:repoId} — the same existence question with no body.
 * </ul>
 *
 * <p>These give a caller (qits-projects) a way to provision a repository over the wire instead of
 * {@code git init --bare} on the shared volume — see {@code projects-volume-decoupling-plan.md}
 * §2. There is deliberately no delete verb.
 *
 * <p>And one route on the bare collection:
 *
 * <ul>
 *   <li>{@code GET /git} — {@code {"repositories": ["<repoId>", …]}}, every repository
 *       this host serves, sorted lexicographically.
 * </ul>
 *
 * <p>That listing was withheld on purpose while nothing needed it (the same plan's §2.1, "no
 * enumerate verb"); the decision is reversed rather than left standing, because qits-ci's trigger
 * engine has to enumerate candidates before it can fire an event-triggered pipeline, and a caller
 * that cannot ask has to be told out of band by whoever creates a repository. It is one segment
 * shorter than every route above it, so it shadows none of them.
 *
 * <p>And two <b>content reads</b>, on the id-addressed base:
 *
 * <ul>
 *   <li>{@code GET …/:repoId/blob/:rev/<path>} — the raw bytes at that path in that revision.
 *   <li>{@code GET …/:repoId/tree/:rev[/<path>]} — {@code {"entries":[{"name","type"}]}} for the
 *       directory there; no path is the root tree.
 * </ul>
 *
 * <p>The wire protocol has no blob-at-path verb, so a consumer that wanted one file had to keep a
 * local clone and re-fetch it. Both routes answer at any revision the repository holds — a full sha
 * as readily as a branch name — which is what lets a post-receive consumer read the exact pushed
 * commit instead of racing the branch, and both carry the resolved commit in {@value
 * #COMMIT_SHA_HEADER}. They are reads, but follow the same Git HTTP authentication policy as every
 * other route here.
 */
@ApplicationScoped
public class GitHostRoutes {

  private static final Logger LOG = Logger.getLogger(GitHostRoutes.class);

  /**
   * Repo ids are UUIDs; allow only their character set, no path separators or leading dash — so a
   * traversal-shaped id ({@code ..}, a slash, a dotted name) is refused rather than looked up.
   */
  private static final String REPO_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9-]{0,63}";

  /**
   * The mount point. It was {@code /artifacts/git} while the host lived inside qits-artifacts;
   * standing alone it drops the borrowed prefix, because qits-gateway routes VERBATIM by prefix.
   *
   * <p><b>It is not this service's segment.</b> The segment is {@code /githost} — the SPA, the REST
   * API and the non-application root are all under it — and {@code /git} is an EXTRA prefix on the
   * same gateway entry, carried because git owns this spelling and no config key can move it.
   *
   * <p>Git treats whatever comes before the suffixes as an opaque base and appends {@code
   * /info/refs}, {@code /git-upload-pack} and {@code /git-receive-pack} itself, so a base of any
   * depth works. This one is a cross-repo contract — qits-ci's pipeline-config fetch and the
   * workspace daemon's provisioner both clone from it — so moving it is a cutover, not an edit.
   */
  private static final String BASE = "/git";
  private static final String BOOTSTRAP_BASE = "/bootstrap-git";

  private static final String UPLOAD = "git-upload-pack";
  private static final String RECEIVE = "git-receive-pack";

  private static final String ADMIN_ROLE = "qits:admin";
  private static final String SYSTEM_ROLE = "qits:system";
  private static final String EXTERNAL_GIT_ROLE = "qits:git:external";
  private static final String PUSH_ACCESS_KEY = GitHostRoutes.class.getName() + ".pushAccess";

  /**
   * The resolved commit, on every content response. <b>Not</b> an {@code X-Qits-} name: qits-gateway
   * strips that whole prefix unconditionally, so a header spelled that way would reach a caller
   * through qits-net and vanish through the gateway — the same trap that makes the push bypasses
   * push options rather than headers.
   *
   * <p>It is what makes a read at a branch name useful: the caller learns which commit it actually
   * got, so a later read can be pinned to that sha rather than to a ref that has since moved.
   */
  static final String COMMIT_SHA_HEADER = "Git-Commit-Sha";

  /**
   * The largest blob {@link #serveBlob} hands back; anything larger is a {@code 413} naming this
   * number. Sized for source files — a pipeline config, a lockfile, a Dockerfile — because that is
   * what a content read is for; a consumer that wants a repository's bytes in bulk clones it.
   *
   * <p>Stated as a constant rather than a config key on purpose: it is a property of what this route
   * is for, not a deployment's choice, and a knob would invite raising it until the read is a
   * memory allocation on an authenticated route. The whole blob is held in memory —
   * the same reason {@link #maxPackSize} sits far below the wire ceiling.
   */
  private static final int MAX_BLOB_BYTES = 8 * 1024 * 1024;

  /**
   * What a {@code :rev} may look like: a full sha or a ref name. No leading dash or dot, no {@code
   * ..}, no whitespace — the argv-safety discipline every user-supplied ref gets — and no {@code
   * ^~@{}:}, which keeps a rev a NAME rather than a revision expression. {@code HEAD@{2}} and
   * {@code main^{tree}} are things {@link Repository#resolve} would happily answer; refusing them
   * keeps this route's contract to "a sha or a ref" instead of to whatever JGit's parser accepts.
   *
   * <p>Slashes are allowed, because {@code feature/x} is a branch name. They cannot arrive as path
   * separators — {@code :rev} is one path segment — so a slashy ref is written {@code %2F}, decoded
   * by {@link #decodePercent} before this check runs.
   */
  private static final String REV_PATTERN = "[A-Za-z0-9][A-Za-z0-9._/-]{0,254}";

  /** The longest repository-relative path a content read will look up. */
  private static final int MAX_PATH_LENGTH = 1024;

  /**
   * {@code -o qits.no-ci} — "do not build this push". It <b>suppresses no event</b>: it becomes
   * {@code suppressCi} on every {@code SCMPublishCommit} the push produces, and each consumer
   * decides what that means to it. A run engine skips the build; a backup trigger ignores the flag,
   * because a backup is owed even for a push CI is meant to leave alone.
   *
   * <p>That is the one behaviour change of the move off the HTTP fan-out, and it is deliberate: the
   * notifier decided FOR its two consumers, which put the option's meaning in the publisher and left
   * no room for a third consumer to have an opinion.
   *
   * <p>Read in {@link #service}'s post-receive lambda, not by {@link ProtectedRefHook}: it grants no
   * write, so it is not a bypass of anything. See {@code ProtectedRefHook}'s "two bypasses" javadoc,
   * third bullet.
   */
  private static final String NO_CI_OPTION = "qits.no-ci";

  /**
   * The JSON body limit for the lifecycle {@code PUT} — a {@code {"defaultBranch": "…"}} document,
   * nowhere near what a pack needs. Stated explicitly rather than inherited, for the same reason
   * {@link #maxPackSize} is: {@code BodyHandler.create()} defaults to 10 MiB, and a bound this far
   * under that is what keeps a stray large body a 413 instead of a memory allocation.
   */
  private static final long LIFECYCLE_BODY_LIMIT = 4096;

  /**
   * The largest pack this host accepts, and the reason it is spelled out rather than inherited.
   *
   * <p>{@code BodyHandler.create()} is <em>not</em> unlimited: vertx-web's {@code BodyHandlerImpl}
   * defaults its {@code bodyLimit} to 10 MiB. So until this existed every push over 10 MB was
   * silently 413'd — not at the 64M this service's config comment claimed, and not at the global
   * ceiling the README described either. Both were wrong about which number bound.
   *
   * <p>It must also stay well below {@code quarkus.http.limits.max-body-size}, which the OCI
   * registry raised to 1088M. That ceiling is sized for a layer that streams to disk; a pack goes
   * through a {@link BodyHandler} into memory, so inheriting it would turn a large push into a
   * gigabyte-sized heap allocation on an authenticated route.
   */
  @ConfigProperty(name = "qits.repositories.git.max-pack-size", defaultValue = "64M")
  MemorySize maxPackSize;

  @ConfigProperty(name = "qits.bootstrap.ingress.git.enabled", defaultValue = "false")
  boolean bootstrapIngressEnabled;

  @ConfigProperty(name = "qits.bootstrap.ingress.git.secret-hash", defaultValue = "")
  String bootstrapIngressSecretHash;

  @ConfigProperty(name = "qits.bootstrap.ingress.git.repository", defaultValue = "")
  String bootstrapIngressRepository;

  @ConfigProperty(name = "qits.bootstrap.ingress.git.ref-pattern", defaultValue = "")
  String bootstrapIngressRefPattern;

  @ConfigProperty(name = "qits.bootstrap.ingress.git.expires-at", defaultValue = "1970-01-01T00:00:00Z")
  Instant bootstrapIngressExpiresAt;

  @Inject Instance<RepositoryNameResolver> repositoryNames;

  /**
   * Who is told about a push. A port with any number of implementations, {@code 0} included: the
   * shipped one turns the receive commands into {@code SCMPublish*}/{@code SCMDelete*} events and
   * hands them to the platform bus, and a deployment without it serves git and announces nothing.
   */
  @Inject Instance<ScmAnnouncer> announcers;

  @Inject ProtectedRefHook protectedRefs;

  /**
   * Where repositories live: packs, pack indexes and refs as blobs in this service's own store. The
   * one seam between these routes and the bytes, and the only class here that knows there is one.
   */
  @Inject GitRepositoryProvider provider;

  /** A resolved repository plus the id it resolved to (the post-receive hook needs the id). */
  private record OpenedRepo(String repoId, Repository repo) {}

  /**
   * A snapshot of the verified HTTP identity made before Vert.x moves receive-pack work to a worker
   * thread. External is deliberately a restriction, not a grant: an identity that also holds an
   * ordinary admin or system role keeps that privileged role's established behaviour.
   */
  record PushAccess(boolean externalOnly, String externalRefPattern) {
    static PushAccess from(SecurityIdentity identity) {
      boolean privileged =
          identity != null && (identity.hasRole(ADMIN_ROLE) || identity.hasRole(SYSTEM_ROLE));
      if (privileged || identity == null || !identity.hasRole(EXTERNAL_GIT_ROLE)) {
        return new PushAccess(false, null);
      }
      String pattern =
          identity.getPrincipal() instanceof JsonWebToken jwt
              ? jwt.claim("git_ref_pattern").map(String::valueOf).orElse(null)
              : null;
      return new PushAccess(true, pattern);
    }
  }

  /**
   * Register the routes on the main Vert.x router (root path — NOT under {@code
   * quarkus.rest.path}). Blocking: JGit's UploadPack/ReceivePack do synchronous stream I/O against
   * whatever storage backs the repository, so they run on a worker thread. The POST bodies
   * (packfiles) are buffered by a
   * {@link BodyHandler} first — fine at qits' single-node scale, and bounded by {@link
   * #maxPackSize} rather than by the global wire ceiling, which the OCI registry raised past
   * anything that should be held in memory.
   *
   * <p>{@link #BASE} is spelled out here as a literal because these are raw Vert.x routes: no
   * config key moves them, and there is nothing else in this process for them to be relative to.
   * The gateway routes {@code /git/*} verbatim, so the segment has to be in the route.
   *
   * <p>The two-segment name-addressed routes and the one-segment id-addressed routes never collide:
   * they differ in path length, so Vert.x dispatches each unambiguously. Prefixing both with the
   * same fixed segment preserves that — four path segments against five — and every handler reads
   * its parameters {@link RoutingContext#pathParam(String) by name}, never by position, so nothing
   * here depends on where in the path a parameter happens to sit.
   */
  void init(@Observes Router router) {
    // The collection, on the base itself: one segment shorter than every route below, so Vert.x can
    // never dispatch a per-repo request here or a collection request there — the same path-length
    // argument the two addressing schemes rest on. Blocking like the rest, because enumerating is
    // a query against the pack catalog.
    router.get(BASE).blockingHandler(this::listRepositories);

    router.get(BASE + "/:repoId/info/refs").blockingHandler(rc -> infoRefs(rc, open(rc, "repoId")));
    router
        .post(BASE + "/:repoId/git-upload-pack")
        .handler(packBodyHandler())
        .blockingHandler(rc -> service(rc, UPLOAD, open(rc, "repoId")));
    router
        .post(BASE + "/:repoId/git-receive-pack")
        .handler(packBodyHandler())
        .handler(this::snapshotPushAccess)
        .blockingHandler(rc -> service(rc, RECEIVE, open(rc, "repoId")));

    router
        .put(BASE + "/:repoId")
        .handler(lifecycleBodyHandler())
        .blockingHandler(this::createRepository);
    router.get(BASE + "/:repoId").blockingHandler(this::describeRepository);
    router.head(BASE + "/:repoId").blockingHandler(this::headRepository);

    // The content reads, BEFORE the name-addressed scheme: they carry a literal second segment
    // (`blob`, `tree`) where that scheme carries a repository NAME, so a project holding a
    // repository called `blob` is the one place the two overlap — see contentReadIsNotAClone, which
    // hands that request back to the router rather than answering it. Registered with regexes so
    // the tail can hold slashes, the MavenPaths/NpmPaths shape: every group is (?<named>…) or
    // (?:…), because vertx-web silently falls back to positional param0…N when the count disagrees.
    // The rev group is deliberately LOOSE here and validated in the handler — a malformed rev is a
    // 400 that says so, not a 404 that sends the caller looking for a repository.
    router.getWithRegex(blobRoute()).blockingHandler(this::serveBlob);
    router.getWithRegex(treeRoute("")).blockingHandler(this::serveTree);
    router.getWithRegex(treeRoute("/(?<path>.*)")).blockingHandler(this::serveTree);

    router
        .get(BASE + "/:projectId/:repoName/info/refs")
        .blockingHandler(rc -> infoRefs(rc, openByName(rc)));
    router
        .post(BASE + "/:projectId/:repoName/git-upload-pack")
        .handler(packBodyHandler())
        .blockingHandler(rc -> service(rc, UPLOAD, openByName(rc)));
    router
        .post(BASE + "/:projectId/:repoName/git-receive-pack")
        .handler(packBodyHandler())
        .handler(this::snapshotPushAccess)
        .blockingHandler(rc -> service(rc, RECEIVE, openByName(rc)));

    // No normal deployment enables these routes.  The short-lived bootstrap ingress rewrites only
    // the three smart-HTTP shapes here and carries an opaque capability whose hash, repository,
    // ref namespace and deadline exist solely in the seed compose environment.
    if (bootstrapIngressEnabled) {
      BootstrapIngressCredential credential = new BootstrapIngressCredential(
          new BootstrapIngressCredential.Config(true, bootstrapIngressSecretHash,
              bootstrapIngressRepository, bootstrapIngressRefPattern, bootstrapIngressExpiresAt));
      router.get(BOOTSTRAP_BASE + "/:repoId/info/refs")
          .handler(rc -> bootstrapPermit(rc, credential))
          .blockingHandler(rc -> infoRefs(rc, open(rc, "repoId")));
      router.post(BOOTSTRAP_BASE + "/:repoId/git-upload-pack")
          .handler(rc -> bootstrapPermit(rc, credential))
          .handler(packBodyHandler())
          .blockingHandler(rc -> service(rc, UPLOAD, open(rc, "repoId")));
      router.post(BOOTSTRAP_BASE + "/:repoId/git-receive-pack")
          .handler(rc -> bootstrapPermit(rc, credential))
          .handler(packBodyHandler())
          .handler(rc -> snapshotBootstrapPushAccess(rc, credential))
          .blockingHandler(rc -> service(rc, RECEIVE, open(rc, "repoId")));
    }
  }

  private void bootstrapPermit(RoutingContext rc, BootstrapIngressCredential credential) {
    if (!credential.permits(rc.request().getHeader(BootstrapIngressCredential.HEADER),
        rc.pathParam("repoId"), Instant.now())) {
      rc.response().setStatusCode(401).end();
      return;
    }
    rc.next();
  }

  private void snapshotBootstrapPushAccess(RoutingContext rc, BootstrapIngressCredential credential) {
    rc.put(PUSH_ACCESS_KEY, new PushAccess(true, credential.refPattern()));
    rc.next();
  }

  /**
   * The pack body handler, with its limit stated. See {@link #maxPackSize} for why leaving it at
   * {@code BodyHandler.create()}'s default was a bug rather than a choice. File uploads are off:
   * these routes carry a single binary pack, never a multipart form, and the default would have the
   * handler spooling into a {@code file-uploads} directory nothing ever reads.
   */
  private BodyHandler packBodyHandler() {
    return BodyHandler.create(false).setBodyLimit(maxPackSize.asLongValue());
  }

  /** The lifecycle {@code PUT}'s body handler. See {@link #LIFECYCLE_BODY_LIMIT} for the number. */
  private BodyHandler lifecycleBodyHandler() {
    return BodyHandler.create(false).setBodyLimit(LIFECYCLE_BODY_LIMIT);
  }

  /** {@code GET …/info/refs?service=…} — the smart-HTTP ref advertisement. */
  private void infoRefs(RoutingContext rc, OpenedRepo opened) {
    String service = rc.request().getParam("service");
    // try(repo) wraps the whole body — including the early returns — so a repo opened eagerly by
    // the
    // route handler is closed on every path (the 403 dumb-HTTP branch below would otherwise leak
    // it).
    // A null repo is a no-op for try-with-resources.
    try (Repository repo = opened == null ? null : opened.repo()) {
      if (!UPLOAD.equals(service) && !RECEIVE.equals(service)) {
        // Dumb-HTTP (no ?service=) is unsupported; only the smart protocol is served.
        rc.response().setStatusCode(403).end("only smart HTTP is supported");
        return;
      }
      if (repo == null) {
        rc.response().setStatusCode(404).end();
        return;
      }
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      PacketLineOut pck = new PacketLineOut(buf);
      pck.writeString("# service=" + service + "\n");
      pck.end(); // flush-pkt (0000) between the service line and the advertisement
      PacketLineOutRefAdvertiser adv = new PacketLineOutRefAdvertiser(pck);
      if (UPLOAD.equals(service)) {
        UploadPack up = new UploadPack(repo);
        up.setBiDirectionalPipe(false);
        up.sendAdvertisedRefs(adv);
      } else {
        ReceivePack rp = new ReceivePack(repo);
        rp.setBiDirectionalPipe(false);
        // The ADVERTISEMENT half of push options, and the one that is easy to miss: a client only
        // sends `-o` if the capability was offered here, so without this line the options in
        // service() below are silently never seen and every guarded push is simply refused. The two
        // calls are one feature spread over two ReceivePack instances — they move together.
        rp.setAllowPushOptions(true);
        rp.sendAdvertisedRefs(adv);
      }
      rc.response()
          .putHeader("Content-Type", "application/x-" + service + "-advertisement")
          .putHeader("Cache-Control", "no-cache")
          .end(Buffer.buffer(buf.toByteArray()));
    } catch (Exception e) {
      fail(rc, service, e);
    }
  }

  /** {@code POST …/git-(upload|receive)-pack} — the actual fetch/push exchange. */
  private void service(RoutingContext rc, String service, OpenedRepo opened) {
    if (opened == null) {
      rc.response().setStatusCode(404).end();
      return;
    }
    try (Repository repo = opened.repo()) {
      InputStream in = new ByteArrayInputStream(rc.body().buffer().getBytes());
      if ("gzip".equals(rc.request().getHeader("Content-Encoding"))) {
        in = new GZIPInputStream(in);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (UPLOAD.equals(service)) {
        UploadPack up = new UploadPack(repo);
        up.setBiDirectionalPipe(false);
        // The want policy stays JGit's default ADVERTISED. Relaxing it to REACHABLE_COMMIT would
        // make every want for a non-tip object run a reachability walk on this shared worker
        // thread — a DoS lever even on an authenticated route. ci therefore
        // fetches the BRANCH REF and verifies reachability itself (see GitConfigFetcher).
        up.upload(in, out, null);
      } else {
        ReceivePack rp = new ReceivePack(repo);
        rp.setBiDirectionalPipe(false);
        // The RECEIVING half of push options — the advertisement in infoRefs() is the other, and
        // neither works alone. This is the only bypass channel that behaves identically through all
        // three doors this host is reachable through, because options ride inside the pack protocol
        // while qits-gateway strips the whole X-Qits- header prefix unconditionally.
        rp.setAllowPushOptions(true);
        // The default branch's seatbelt. Inert unless qits.repositories.git.protect-default-branch
        // (or this repository's own protection row) says otherwise — see ProtectedRefHook. Bound to
        // the repo id rather than handed the ReceivePack alone, because the override is a row keyed
        // on that id and a DFS repository has no directory to derive it from.
        rp.setPreReceiveHook(preReceiveHook(opened.repoId(), pushAccess(rc)));
        // The post-receive announcement: fires after the ref updates land, still inside receive(),
        // so the repository is readable and the pack's objects are there to be measured. The
        // announcer must not block the push and must not throw — see ScmAnnouncer.
        //
        // Run under the CAUSE the pusher was acting on, if it named one. This is the hand-rolled
        // half of qits-eventstream's causation-over-HTTP: CausationServerFilter does it for every
        // JAX-RS resource method, and this service has none — these are raw Vert.x routes, so the
        // header is read here or the chain breaks at the git host. See causationOf.
        UUID cause = causationOf(rc);
        rp.setPostReceiveHook(
            (pack, commands) ->
                CausationScope.with(
                    cause,
                    () -> announce(opened.repoId(), repo, commands, hasNoCiOption(pack))));
        rp.receive(in, out, null);
      }
      rc.response()
          .putHeader("Content-Type", "application/x-" + service + "-result")
          .putHeader("Cache-Control", "no-cache")
          .end(Buffer.buffer(out.toByteArray()));
    } catch (Exception e) {
      fail(rc, service, e);
    }
  }

  /** Capture the authenticated role decision while the request still runs on the event loop. */
  private void snapshotPushAccess(RoutingContext rc) {
    SecurityIdentity identity =
        rc.user() instanceof QuarkusHttpUser user ? user.getSecurityIdentity() : null;
    rc.put(PUSH_ACCESS_KEY, PushAccess.from(identity));
    rc.next();
  }

  /**
   * The external namespace check runs before the default-branch hook. It rejects every command in
   * a mixed push, so protected-ref policy cannot accidentally leave a permitted command behind.
   */
  private PreReceiveHook preReceiveHook(String repoId, PushAccess access) {
    PreReceiveHook protectedHook = protectedRefs.forRepository(repoId);
    if (!access.externalOnly()) {
      return protectedHook;
    }
    return (pack, commands) -> {
      if (!ExternalRefHook.rejectOutsideExternalBranches(commands, access.externalRefPattern())) {
        protectedHook.onPreReceive(pack, commands);
      }
    };
  }

  private static PushAccess pushAccess(RoutingContext rc) {
    PushAccess access = rc.get(PUSH_ACCESS_KEY);
    // A protected route always runs snapshotPushAccess first. Failing closed for a route wiring
    // regression is safer than treating an external caller as privileged on a worker thread.
    return access == null ? new PushAccess(true, "") : access;
  }

  /**
   * The cause this push is being made <b>because of</b>, out of {@value
   * CausationHeader#NAME}, or {@code null} when the pusher named none.
   *
   * <p>qits-eventstream propagates a cause across an HTTP hop with a pair of JAX-RS filters, and
   * {@code CausationServerFilter} would do exactly this for a resource method. This service has no
   * JAX-RS surface at all — the git protocol is raw Vert.x routes — so the incoming half is read
   * here by hand, and a chain that reaches the git host through, say, a workspace integrate keeps
   * going into the {@code SCMPublish*} events rather than restarting at them.
   *
   * <p><b>Lenient, and that is the contract rather than laziness.</b> Blank and malformed both read
   * as absent: causation is advisory, and a push must never be refused over a field only the chain
   * graph reads. It mirrors {@code CausationHeader.parse}, which is package-private in the library
   * and so cannot be called from here; the parsing is four lines and the semantics are what matter.
   *
   * <p>The header sits inside the gateway's reserved {@code X-Qits-} namespace, which is what makes
   * it unforgeable from outside: qits-gateway drops every client-supplied header with that prefix
   * before it proxies. Service-to-service traffic on qits-net carries it untouched — and that is
   * also the traffic that has a cause worth carrying.
   */
  private static UUID causationOf(RoutingContext rc) {
    String raw = rc.request().getHeader(CausationHeader.NAME);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }

  /**
   * Tells every {@link ScmAnnouncer} on the classpath about a landed push.
   *
   * <p>Wrapped so one announcer cannot take a push down with it: this runs inside {@code
   * ReceivePack.receive}, before the response is written, and the refs are already updated by the
   * time it is reached. A push that succeeded must be reported as a success even if nobody could be
   * told about it.
   */
  private void announce(
      String repoId, Repository repo, Collection<ReceiveCommand> commands, boolean suppressCi) {
    for (ScmAnnouncer announcer : announcers) {
      try {
        announcer.onPostReceive(repoId, repo, commands, suppressCi);
      } catch (Exception e) {
        LOG.warnf(e, "post-receive announcement for %s failed", repoId);
      }
    }
  }

  /** Whether this push carried {@code -o qits.no-ci}. */
  private boolean hasNoCiOption(ReceivePack pack) {
    List<String> options = pack.getPushOptions();
    return options != null && options.contains(NO_CI_OPTION);
  }

  // --- content reads ------------------------------------------------------------------------------

  /** {@code …/:repoId/blob/:rev/<path>} — the path is required, so the tail is {@code .+}. */
  private static String blobRoute() {
    return BASE + "/(?<repoId>" + REPO_ID_PATTERN + ")/blob/(?<rev>[^/]+)/(?<path>.+)";
  }

  /**
   * {@code …/:repoId/tree/:rev} plus {@code suffix} — registered twice, with and without a path,
   * rather than once with an optional group: an unmatched named group is a shape vertx-web's
   * parameter scraping does not have to handle, and two routes cost nothing.
   *
   * <p>A method rather than a constant for the reason {@code MavenPaths.route} is one: a {@code
   * static final String} built from a constant expression is inlined by javac into every reader.
   */
  private static String treeRoute(String suffix) {
    return BASE + "/(?<repoId>" + REPO_ID_PATTERN + ")/tree/(?<rev>[^/]+)" + suffix;
  }

  /**
   * {@code GET …/:repoId/blob/:rev/<path>} — the raw bytes at that path in that revision, {@code
   * application/octet-stream}, with the resolved commit in {@value #COMMIT_SHA_HEADER}.
   *
   * <p>404 for a repository, revision or path that does not resolve, and for a path that resolves
   * to something other than a file (a directory, a symlink, a submodule gitlink — none of them has
   * bytes a consumer could use as file content). 400 for a rev or path this route will not look up
   * at all. 413 for a blob past {@link #MAX_BLOB_BYTES}.
   *
   * <p>A revision is anything the repository holds, reachable from a ref or not. That is the point
   * rather than an oversight: a post-receive consumer reads at the sha it was told about, which the
   * branch may already have moved past. It is not the {@code UploadPack} want policy being relaxed
   * — that stays {@code ADVERTISED}, because a want runs a reachability walk and this does not.
   */
  private void serveBlob(RoutingContext rc) {
    String rev = decodePercent(rc.pathParam("rev"));
    String path = normalizePath(rc.pathParam("path"));
    if (contentReadIsNotAClone(rc, rev, path)) {
      return;
    }
    if (!isValidRev(rev)) {
      rc.response().setStatusCode(400).end("rev must match " + REV_PATTERN);
      return;
    }
    if (!isValidPath(path) || path.isEmpty()) {
      rc.response().setStatusCode(400).end("path must be a repository-relative file path");
      return;
    }
    OpenedRepo opened = open(rc.pathParam("repoId"));
    if (opened == null) {
      rc.response().setStatusCode(404).end();
      return;
    }
    try (Repository repo = opened.repo();
        RevWalk walk = new RevWalk(repo)) {
      RevCommit commit = resolveCommit(repo, walk, rev);
      if (commit == null) {
        rc.response().setStatusCode(404).end();
        return;
      }
      try (TreeWalk found = TreeWalk.forPath(repo, path, commit.getTree())) {
        if (found == null || !isFile(found.getFileMode(0))) {
          rc.response().setStatusCode(404).end();
          return;
        }
        ObjectLoader loader = repo.open(found.getObjectId(0), Constants.OBJ_BLOB);
        if (loader.getSize() > MAX_BLOB_BYTES) {
          rc.response()
              .setStatusCode(413)
              .end("blob is larger than the " + MAX_BLOB_BYTES + " bytes this route serves");
          return;
        }
        rc.response()
            .putHeader("Content-Type", "application/octet-stream")
            .putHeader(COMMIT_SHA_HEADER, commit.name())
            .end(Buffer.buffer(loader.getBytes(MAX_BLOB_BYTES)));
      }
    } catch (MissingObjectException | IncorrectObjectTypeException e) {
      // A well-formed object id this repository does not hold, or holds as the wrong type. Both are
      // "no such content here", which is a 404 rather than the 500 fail() would make of them.
      rc.response().setStatusCode(404).end();
    } catch (Exception e) {
      fail(rc, "git-blob", e);
    }
  }

  /**
   * {@code GET …/:repoId/tree/:rev[/<path>]} — {@code {"entries":[{"name","type"}]}} for the
   * directory at that revision, no path meaning the root tree. 404 when the revision or the path
   * does not resolve, and when the path resolves to something that is not a tree.
   *
   * <p>{@code type} is {@code tree} or {@code blob} and nothing else: a symlink and a submodule
   * gitlink are listed as {@code blob}, because what a caller does with an entry is descend into it
   * or read it, and neither of those can be descended into. The order is the tree's own — git's
   * canonical sort — so it is stable across revisions without this route sorting anything.
   */
  private void serveTree(RoutingContext rc) {
    String rev = decodePercent(rc.pathParam("rev"));
    String path = normalizePath(rc.pathParam("path"));
    if (contentReadIsNotAClone(rc, rev, path)) {
      return;
    }
    if (!isValidRev(rev)) {
      rc.response().setStatusCode(400).end("rev must match " + REV_PATTERN);
      return;
    }
    if (!isValidPath(path)) {
      rc.response().setStatusCode(400).end("path must be a repository-relative directory path");
      return;
    }
    OpenedRepo opened = open(rc.pathParam("repoId"));
    if (opened == null) {
      rc.response().setStatusCode(404).end();
      return;
    }
    try (Repository repo = opened.repo();
        RevWalk walk = new RevWalk(repo)) {
      RevCommit commit = resolveCommit(repo, walk, rev);
      if (commit == null) {
        rc.response().setStatusCode(404).end();
        return;
      }
      ObjectId tree = path.isEmpty() ? commit.getTree() : subtree(repo, commit, path);
      if (tree == null) {
        rc.response().setStatusCode(404).end();
        return;
      }
      JsonArray entries = new JsonArray();
      try (TreeWalk walker = new TreeWalk(repo)) {
        walker.addTree(tree);
        walker.setRecursive(false);
        while (walker.next()) {
          entries.add(
              new JsonObject()
                  .put("name", walker.getNameString())
                  .put("type", FileMode.TREE.equals(walker.getFileMode(0)) ? "tree" : "blob"));
        }
      }
      rc.response()
          .putHeader("Content-Type", "application/json")
          .putHeader(COMMIT_SHA_HEADER, commit.name())
          .end(new JsonObject().put("entries", entries).encode());
    } catch (MissingObjectException | IncorrectObjectTypeException e) {
      rc.response().setStatusCode(404).end();
    } catch (Exception e) {
      fail(rc, "git-tree", e);
    }
  }

  /** The tree object at {@code path}, or {@code null} if there is none or it is not a tree. */
  private ObjectId subtree(Repository repo, RevCommit commit, String path) throws IOException {
    try (TreeWalk found = TreeWalk.forPath(repo, path, commit.getTree())) {
      return found == null || !FileMode.TREE.equals(found.getFileMode(0))
          ? null
          : found.getObjectId(0);
    }
  }

  /**
   * The commit {@code rev} names, or {@code null} if this repository does not hold one.
   *
   * <p>{@link Repository#resolve} takes a full sha or a ref name; a full sha is returned <b>without
   * an existence check</b>, so the miss for a well-formed but unreachable id lands here, in {@code
   * parseCommit}. An annotated tag is peeled, which is what makes a tag name work as a rev.
   */
  private RevCommit resolveCommit(Repository repo, RevWalk walk, String rev) throws IOException {
    ObjectId id;
    try {
      id = repo.resolve(rev);
    } catch (RevisionSyntaxException e) {
      // Guarded by REV_PATTERN already; treated as "no such revision" rather than a 500 in case
      // JGit's parser refuses something the pattern allows.
      return null;
    }
    if (id == null) {
      return null;
    }
    try {
      return walk.parseCommit(id);
    } catch (MissingObjectException | IncorrectObjectTypeException e) {
      return null;
    }
  }

  /**
   * Hands the request back to the router when it is a name-addressed clone rather than a content
   * read, and reports whether it did.
   *
   * <p>{@code /git/<projectId>/<repoName>/info/refs} is the one request shape that
   * matches these routes too — with {@code repoName} spelled {@code blob} or {@code tree}, so
   * {@code rev} comes out {@code info} and {@code path} {@code refs}. Answering it would make a
   * repository unclonable because of what it is called, so {@link RoutingContext#next} lets the
   * name-addressed route have it. Nothing else overlaps: every other route of that scheme is a
   * POST, and the id-addressed ones differ in segment count.
   */
  private boolean contentReadIsNotAClone(RoutingContext rc, String rev, String path) {
    if ("info".equals(rev) && "refs".equals(path)) {
      rc.next();
      return true;
    }
    return false;
  }

  /** Whether the mode names a file whose bytes are its content. */
  private static boolean isFile(FileMode mode) {
    return FileMode.REGULAR_FILE.equals(mode) || FileMode.EXECUTABLE_FILE.equals(mode);
  }

  /** A sha or a ref name, and nothing that would read as an option or a revision expression. */
  private static boolean isValidRev(String rev) {
    return rev != null && !rev.contains("..") && rev.matches(REV_PATTERN);
  }

  /**
   * A repository-relative path: no leading or doubled slash, no {@code .} or {@code ..} segment, no
   * control characters, bounded in length. The empty string is valid and means the root — {@link
   * #serveBlob} refuses it separately, because a blob has no root.
   *
   * <p>Dot segments cannot arrive anyway: vertx-web matches against {@code normalizedPath()}, which
   * collapses them before routing. Checked all the same, because that is a property of the router
   * this route would rather not inherit silently.
   */
  private static boolean isValidPath(String path) {
    if (path == null || path.length() > MAX_PATH_LENGTH) {
      return false;
    }
    if (path.isEmpty()) {
      return true;
    }
    if (path.startsWith("/") || path.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      return false;
    }
    for (String segment : path.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        return false;
      }
    }
    return true;
  }

  /** A path tail with its trailing slashes dropped; {@code null} becomes the root. */
  private static String normalizePath(String raw) {
    String path = decodePercent(raw);
    if (path == null) {
      return "";
    }
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  /**
   * Percent-decodes one path segment. Hand-rolled rather than {@link java.net.URLDecoder}, which
   * also turns {@code +} into a space — a ref named {@code 1.0+build} would decode to one that does
   * not exist. Bytes are collected and read back as UTF-8, so a non-ASCII file name survives.
   */
  private static String decodePercent(String raw) {
    if (raw == null || raw.indexOf('%') < 0) {
      return raw;
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '%' && i + 2 < raw.length()) {
        int high = Character.digit(raw.charAt(i + 1), 16);
        int low = Character.digit(raw.charAt(i + 2), 16);
        if (high >= 0 && low >= 0) {
          bytes.write((high << 4) + low);
          i += 2;
          continue;
        }
      }
      bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  /**
   * {@code GET /git} — {@code {"repositories": [...]}}, every repository this host serves.
   *
   * <p>Sorted here rather than by the provider, so the order is a property of the response.
   * Filtered here for the same reason the id check in {@link
   * #open(String)} is: an id that is not a valid slug cannot be served by any route on this host, so
   * listing one would advertise a repository no caller could clone.
   *
   * <p>The class-wide Git HTTP policy applies here too — repo ids are identifiers rather than
   * UUIDs and the callers are machines on qits-net. A read surface gated on its own would be the
   * piecemeal machine auth this platform has decided against; qits-platform-idp gates these together.
   *
   * <p>An enumeration failure is a 500 by way of {@link #fail}, never an empty list: a trigger
   * engine told "no repositories" stops triggering and reports nothing wrong.
   */
  private void listRepositories(RoutingContext rc) {
    try {
      JsonArray repositories = new JsonArray();
      provider.repositoryIds().stream()
          .filter(repoId -> repoId.matches(REPO_ID_PATTERN))
          .sorted()
          .forEach(repositories::add);
      rc.response()
          .putHeader("Content-Type", "application/json")
          .end(new JsonObject().put("repositories", repositories).encode());
    } catch (Exception e) {
      fail(rc, "git-list", e);
    }
  }

  /**
   * {@code PUT …/:repoId} — create, idempotently. {@code defaultBranch} is validated as a branch
   * name before it reaches {@link GitRepositoryProvider#create}: the same argv-safety discipline
   * every user-supplied ref gets before it reaches JGit.
   */
  private void createRepository(RoutingContext rc) {
    String repoId = rc.pathParam("repoId");
    if (repoId == null || !repoId.matches(REPO_ID_PATTERN)) {
      rc.response().setStatusCode(400).end("repo id must match " + REPO_ID_PATTERN);
      return;
    }
    String defaultBranch = readDefaultBranch(rc);
    if (defaultBranch == null) {
      rc.response()
          .setStatusCode(400)
          .end(
              "defaultBranch must be a non-blank branch name with no leading dash, no \"..\", and"
                  + " no whitespace");
      return;
    }
    try {
      provider.create(repoId, defaultBranch);
      respondRepository(rc, 201, repoId);
    } catch (IOException e) {
      // create() throws IOException both for "already exists" and for any other creation failure,
      // with no subtype to tell them apart. Re-opening does: PUT is idempotent, so a repository
      // that is there now is success (200) regardless of which defaultBranch it already carries —
      // not necessarily the one just requested.
      if (repositoryExists(repoId)) {
        respondRepository(rc, 200, repoId);
      } else {
        LOG.errorf(e, "failed to create git repository %s", repoId);
        rc.response().setStatusCode(500).end();
      }
    } catch (Exception e) {
      fail(rc, "git-create", e);
    }
  }

  /** {@code GET …/:repoId} — {@code {"repoId", "defaultBranch"}}, or 404. */
  private void describeRepository(RoutingContext rc) {
    String repoId = rc.pathParam("repoId");
    if (repoId == null || !repoId.matches(REPO_ID_PATTERN)) {
      rc.response().setStatusCode(400).end("repo id must match " + REPO_ID_PATTERN);
      return;
    }
    respondRepository(rc, 200, repoId);
  }

  /** {@code HEAD …/:repoId} — the same existence question as {@link #describeRepository}, no body. */
  private void headRepository(RoutingContext rc) {
    String repoId = rc.pathParam("repoId");
    if (repoId == null || !repoId.matches(REPO_ID_PATTERN)) {
      rc.response().setStatusCode(400).end();
      return;
    }
    try (Repository repo = provider.open(repoId)) { // null repo: a no-op close
      rc.response().setStatusCode(repo == null ? 404 : 200).end();
    } catch (Exception e) {
      fail(rc, "git-head", e);
    }
  }

  /**
   * Opens {@code repoId} and writes {@code {"repoId", "defaultBranch"}} at {@code status}, or 404 if
   * the store holds no such repository. Shared by the create 200/201 arms and by {@link
   * #describeRepository}, so all three report the repository's own {@code HEAD} rather than trusting
   * whatever a caller asked for.
   */
  private void respondRepository(RoutingContext rc, int status, String repoId) {
    try (Repository repo = provider.open(repoId)) { // null repo: a no-op close
      if (repo == null) {
        rc.response().setStatusCode(404).end();
        return;
      }
      rc.response()
          .setStatusCode(status)
          .putHeader("Content-Type", "application/json")
          .end(
              new JsonObject()
                  .put("repoId", repoId)
                  .put("defaultBranch", defaultBranchOf(repo))
                  .encode());
    } catch (Exception e) {
      fail(rc, "git-lifecycle", e);
    }
  }

  /** Whether the store already holds {@code repoId}. */
  private boolean repositoryExists(String repoId) {
    try (Repository repo = provider.open(repoId)) { // null repo: a no-op close
      return repo != null;
    }
  }

  /** The repository's {@code HEAD}, as a short branch name, or {@code null} if it names none. */
  private String defaultBranchOf(Repository repo) throws IOException {
    String full = repo.getFullBranch();
    return full != null && full.startsWith(Constants.R_HEADS)
        ? full.substring(Constants.R_HEADS.length())
        : full;
  }

  /**
   * Reads and validates {@code defaultBranch} from the request body, or {@code null} if the body is
   * missing, malformed, or the value fails validation.
   */
  private String readDefaultBranch(RoutingContext rc) {
    Buffer body = rc.body().buffer();
    if (body == null || body.length() == 0) {
      return null;
    }
    String candidate;
    try {
      candidate = new JsonObject(body).getString("defaultBranch");
    } catch (Exception e) {
      return null;
    }
    return isValidBranchName(candidate) ? candidate : null;
  }

  /**
   * Non-blank, no leading dash (an option-injection shape), no {@code ..}, no whitespace — the same
   * argv-safety discipline every user-supplied ref is checked against before it reaches JGit.
   */
  private static boolean isValidBranchName(String name) {
    return name != null
        && !name.isBlank()
        && !name.startsWith("-")
        && !name.contains("..")
        && name.chars().noneMatch(Character::isWhitespace);
  }

  /** Opens the repo named by the {@code repoId} path param (the id-addressed scheme). */
  private OpenedRepo open(RoutingContext rc, String param) {
    return open(rc.pathParam(param));
  }

  /**
   * Validates the id and hands it to the {@link GitRepositoryProvider}. Returns {@code null} (→
   * 404) for an id that isn't a valid repo-id slug or that the store does not hold; the caller
   * closes the returned repo.
   *
   * <p><b>This is the whole storage seam.</b> Everything above it — {@link #infoRefs} and {@link
   * #service} — takes a {@code Repository} and never learns that its packs and refs are blobs.
   *
   * <p>The slug check stays <b>here</b> rather than moving into the provider, because it is a
   * property of the url: nothing under this seam touches a filesystem, so a traversal-shaped id
   * would simply be an unknown id there, and it has to be refused rather than looked up.
   *
   * <p>A store that cannot answer <b>throws</b> rather than returning null, and nothing here catches
   * it: the blocking handler hands it to Vert.x, which answers 500. Only a clean "no such
   * repository" is a 404.
   */
  private OpenedRepo open(String repoId) {
    if (repoId == null || !repoId.matches(REPO_ID_PATTERN)) {
      return null;
    }
    Repository repo = provider.open(repoId);
    return repo == null ? null : new OpenedRepo(repoId, repo);
  }

  /**
   * Opens the repository addressed by {@code /git/:projectId/:repoName}: strips an
   * optional {@code
   * .git} suffix, resolves {@code (projectId, name)} through the {@link RepositoryNameResolver} to
   * a repo id, then opens that repo through the provider. The path segments are only lookup
   * keys (never filesystem paths), and the resolved id is re-validated by {@link
   * #open(String)}, so traversal is impossible. With no resolver configured this is a 404.
   */
  private OpenedRepo openByName(RoutingContext rc) {
    String projectId = rc.pathParam("projectId");
    String repoName = rc.pathParam("repoName");
    if (projectId == null || repoName == null || repositoryNames.isUnsatisfied()) {
      return null;
    }
    String name =
        repoName.endsWith(".git") ? repoName.substring(0, repoName.length() - 4) : repoName;
    String repoId = repositoryNames.get().resolveRepositoryId(projectId, name).orElse(null);
    return open(repoId);
  }

  private void fail(RoutingContext rc, String service, Exception e) {
    LOG.errorf(e, "git %s failed", service);
    if (!rc.response().headWritten()) {
      rc.response().setStatusCode(500).end();
    } else {
      rc.response().end();
    }
  }
}
