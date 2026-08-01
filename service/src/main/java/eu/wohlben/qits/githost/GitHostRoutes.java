package eu.wohlben.qits.githost;

import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser.PacketLineOutRefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The in-process git smart-HTTP server, mounted at {@code /artifacts/git/*} so workspace containers
 * can clone and push over {@code http://<qits-host>:<port>/artifacts/git/<repoId>}.
 *
 * <p>Implemented as plain Vert.x routes driving JGit's {@link UploadPack}/{@link ReceivePack}
 * directly — deliberately NOT as a servlet. qits used to host this with JGit's {@code GitServlet}
 * on {@code quarkus-undertow}, but undertow's presence breaks Quinoa's production static serving of
 * the Angular SPA (bundled assets 404 in the packaged fast-jar; see {@code
 * docs/issues/2026-07-15_packaged-spa-not-served.md}). Keeping the git host off the servlet stack
 * lets Quinoa serve the UI exactly as it does in a plain Quinoa app.
 *
 * <p>No authentication: repo ids are capability UUIDs, and the callers are workspace containers,
 * which cannot hold a user token — so {@code /artifacts/git/*} deliberately stays on {@code
 * QitsAuthPolicy}'s public list in every auth build variant (container traffic reaches qits directly on qits-net,
 * bypassing any forward-auth proxy — see auth-core's {@code PublicPaths}). Anonymous fetch AND push
 * are both enabled. The git CLI ({@code GitExecutor}) remains the only thing that mutates
 * repositories; JGit here speaks the wire protocol and nothing else.
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
 *   <li><b>id-addressed</b> {@code /artifacts/git/:repoId} — the opaque UUID, handed straight to the
 *       storage backend (back-compat: already-provisioned containers, metadata, discovery).
 *   <li><b>name-addressed</b> {@code /artifacts/git/:projectId/:repoName} — a project's
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
 */
@ApplicationScoped
public class GitHostRoutes {

  private static final Logger LOG = Logger.getLogger(GitHostRoutes.class);

  /**
   * Repo ids are UUIDs; allow only their character set, no path separators or leading dash — so a
   * traversal-shaped id ({@code ..}, a slash, a dotted name) can never escape the data dir.
   */
  private static final String REPO_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9-]{0,63}";

  /**
   * The mount point, {@code /<gateway segment>/git}. {@code git} is a second-level segment beside
   * {@code api} — it is a wire protocol spoken by {@code git}, not a JSON API, and it appears in no
   * OpenAPI document. Git treats whatever comes before the suffixes as an opaque base and appends
   * {@code /info/refs}, {@code /git-upload-pack} and {@code /git-receive-pack} itself, so a base of
   * any depth works; this one is a cross-repo contract (qits-ci's pipeline-config fetch and the
   * workspace daemon's provisioner both clone from it).
   */
  private static final String BASE = "/artifacts/git";

  private static final String UPLOAD = "git-upload-pack";
  private static final String RECEIVE = "git-receive-pack";

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
   * gigabyte-sized heap allocation on a deliberately unauthenticated route.
   */
  @ConfigProperty(name = "qits.repositories.git.max-pack-size", defaultValue = "64M")
  MemorySize maxPackSize;

  @Inject Instance<RepositoryNameResolver> repositoryNames;

  @Inject CiPostReceiveNotifier ciNotifier;

  @Inject ProtectedRefHook protectedRefs;

  /**
   * Where repositories live. Two backends ship in the same binary and {@code
   * qits.repositories.git.storage} picks one; nothing in this class knows which, because {@link
   * #open} is the only method that has to.
   */
  @Inject GitRepositoryBackend backend;

  /** A resolved repository plus the id it resolved to (the post-receive hook needs the id). */
  private record OpenedRepo(String repoId, Repository repo) {}

  /**
   * Register the routes on the main Vert.x router (root path — NOT under {@code
   * quarkus.rest.path}). Blocking: JGit's UploadPack/ReceivePack do synchronous stream I/O against
   * whatever storage backs the repository, so they run on a worker thread. The POST bodies
   * (packfiles) are buffered by a
   * {@link BodyHandler} first — fine at qits' single-node scale, and bounded by {@link
   * #maxPackSize} rather than by the global wire ceiling, which the OCI registry raised past
   * anything that should be held in memory.
   *
   * <p>{@link #BASE} is spelled out here as a literal because these are raw Vert.x routes: changing
   * {@code quarkus.rest.path} moves the JAX-RS surface and leaves these exactly where they were.
   * The gateway routes {@code /artifacts/*} verbatim, so the segment has to be in the route.
   *
   * <p>The two-segment name-addressed routes and the one-segment id-addressed routes never collide:
   * they differ in path length, so Vert.x dispatches each unambiguously. Prefixing both with the
   * same fixed segment preserves that — four path segments against five — and every handler reads
   * its parameters {@link RoutingContext#pathParam(String) by name}, never by position, so nothing
   * here depends on where in the path a parameter happens to sit.
   */
  void init(@Observes Router router) {
    router.get(BASE + "/:repoId/info/refs").blockingHandler(rc -> infoRefs(rc, open(rc, "repoId")));
    router
        .post(BASE + "/:repoId/git-upload-pack")
        .handler(packBodyHandler())
        .blockingHandler(rc -> service(rc, UPLOAD, open(rc, "repoId")));
    router
        .post(BASE + "/:repoId/git-receive-pack")
        .handler(packBodyHandler())
        .blockingHandler(rc -> service(rc, RECEIVE, open(rc, "repoId")));

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
        .blockingHandler(rc -> service(rc, RECEIVE, openByName(rc)));
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
        // thread — a DoS lever on a route that is deliberately unauthenticated. ci therefore
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
        rp.setPreReceiveHook(protectedRefs.forRepository(opened.repoId()));
        // The literal post-receive event the CI pipelines are named after (docs/epics/qits-ci/):
        // fires after the ref updates land, still inside receive() — the notifier is
        // fire-and-forget so the push response is never delayed.
        rp.setPostReceiveHook(
            (pack, commands) -> ciNotifier.onPostReceive(opened.repoId(), commands));
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

  /** Opens the repo named by the {@code repoId} path param (the id-addressed scheme). */
  private OpenedRepo open(RoutingContext rc, String param) {
    return open(rc.pathParam(param));
  }

  /**
   * Validates the id and hands it to the selected {@link GitRepositoryProvider}. Returns {@code
   * null} (→ 404) for an id that isn't a valid repo-id slug or that the backend does not hold; the
   * caller closes the returned repo.
   *
   * <p><b>This is the whole storage seam.</b> Everything above it — {@link #infoRefs} and {@link
   * #service} — takes a {@code Repository} and cannot tell a bare on the shared volume from a
   * repository whose packs and refs are blobs in this service's own store.
   *
   * <p>The slug check stays <b>here</b> rather than moving into a provider, because it is a property
   * of the url and not of a backend: the file backend joins the id to a path and needs it, the DFS
   * backend never touches a filesystem and would not — and a traversal-shaped id has to be refused
   * identically either way.
   */
  private OpenedRepo open(String repoId) {
    if (repoId == null || !repoId.matches(REPO_ID_PATTERN)) {
      return null;
    }
    Repository repo = backend.provider().open(repoId);
    return repo == null ? null : new OpenedRepo(repoId, repo);
  }

  /**
   * Opens the repository addressed by {@code /artifacts/git/:projectId/:repoName}: strips an
   * optional {@code
   * .git} suffix, resolves {@code (projectId, name)} through the {@link RepositoryNameResolver} to
   * a repo id, then opens that repo through the storage backend. The path segments are only lookup
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
