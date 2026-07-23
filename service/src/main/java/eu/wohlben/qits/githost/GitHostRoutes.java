package eu.wohlben.qits.githost;

import eu.wohlben.qits.domain.repository.persistence.RepositoryNameRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser.PacketLineOutRefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The in-process git smart-HTTP server, mounted at {@code /git/*} so workspace containers can clone
 * and push over {@code http://<qits-host>:<port>/git/<repoId>}.
 *
 * <p>Implemented as plain Vert.x routes driving JGit's {@link UploadPack}/{@link ReceivePack}
 * directly — deliberately NOT as a servlet. qits used to host this with JGit's {@code GitServlet}
 * on {@code quarkus-undertow}, but undertow's presence breaks Quinoa's production static serving of
 * the Angular SPA (bundled assets 404 in the packaged fast-jar; see {@code
 * docs/issues/2026-07-15_packaged-spa-not-served.md}). Keeping the git host off the servlet stack
 * lets Quinoa serve the UI exactly as it does in a plain Quinoa app.
 *
 * <p>No authentication: repo ids are capability UUIDs, and the callers are workspace containers,
 * which cannot hold a user token — so {@code /git/*} deliberately stays on {@code QitsAuthPolicy}'s
 * public list in every auth build variant (container traffic reaches qits directly on qits-net,
 * bypassing any forward-auth proxy — see auth-core's {@code PublicPaths}). Anonymous fetch AND push
 * are both enabled. The git CLI ({@code GitExecutor}) remains the only thing that mutates
 * repositories; JGit here speaks the wire protocol and nothing else.
 *
 * <p>Two addressing schemes, both served:
 *
 * <ul>
 *   <li><b>id-addressed</b> {@code /git/:repoId} — the opaque UUID, resolving directly to {@code
 *       <data-dir>/<repoId>/origin} (back-compat: already-provisioned containers, metadata,
 *       discovery).
 *   <li><b>name-addressed</b> {@code /git/:projectId/:repoName} — a project's repositories served
 *       as siblings, {@code repoName} resolved through the {@link RepositoryNameRepository} alias
 *       table to a repo id. This is what lets committed relative submodule urls ({@code
 *       ../<name>.git}) resolve natively against a sibling — no {@code submodule.<name>.url}
 *       override.
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

  private static final String UPLOAD = "git-upload-pack";
  private static final String RECEIVE = "git-receive-pack";

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  @Inject RepositoryNameRepository repositoryNames;

  /**
   * Register the routes on the main Vert.x router (root path — NOT under {@code
   * quarkus.rest.path}). Blocking: JGit's UploadPack/ReceivePack do synchronous stream I/O against
   * the on-disk bare, so they run on a worker thread. The POST bodies (packfiles) are buffered by a
   * {@link BodyHandler} first — fine at qits' single-node scale.
   *
   * <p>The two-segment name-addressed routes and the one-segment id-addressed routes never collide:
   * they differ in path length, so Vert.x dispatches each unambiguously.
   */
  void init(@Observes Router router) {
    router.get("/git/:repoId/info/refs").blockingHandler(rc -> infoRefs(rc, open(rc, "repoId")));
    router
        .post("/git/:repoId/git-upload-pack")
        .handler(BodyHandler.create())
        .blockingHandler(rc -> service(rc, UPLOAD, open(rc, "repoId")));
    router
        .post("/git/:repoId/git-receive-pack")
        .handler(BodyHandler.create())
        .blockingHandler(rc -> service(rc, RECEIVE, open(rc, "repoId")));

    router
        .get("/git/:projectId/:repoName/info/refs")
        .blockingHandler(rc -> infoRefs(rc, openByName(rc)));
    router
        .post("/git/:projectId/:repoName/git-upload-pack")
        .handler(BodyHandler.create())
        .blockingHandler(rc -> service(rc, UPLOAD, openByName(rc)));
    router
        .post("/git/:projectId/:repoName/git-receive-pack")
        .handler(BodyHandler.create())
        .blockingHandler(rc -> service(rc, RECEIVE, openByName(rc)));
  }

  /** {@code GET …/info/refs?service=…} — the smart-HTTP ref advertisement. */
  private void infoRefs(RoutingContext rc, Repository repo) {
    String service = rc.request().getParam("service");
    // try(repo) wraps the whole body — including the early returns — so a repo opened eagerly by
    // the
    // route handler is closed on every path (the 403 dumb-HTTP branch below would otherwise leak
    // it).
    // A null repo is a no-op for try-with-resources.
    try (repo) {
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
  private void service(RoutingContext rc, String service, Repository repo) {
    if (repo == null) {
      rc.response().setStatusCode(404).end();
      return;
    }
    try (repo) {
      InputStream in = new ByteArrayInputStream(rc.body().buffer().getBytes());
      if ("gzip".equals(rc.request().getHeader("Content-Encoding"))) {
        in = new GZIPInputStream(in);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (UPLOAD.equals(service)) {
        UploadPack up = new UploadPack(repo);
        up.setBiDirectionalPipe(false);
        up.upload(in, out, null);
      } else {
        ReceivePack rp = new ReceivePack(repo);
        rp.setBiDirectionalPipe(false);
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
  private Repository open(RoutingContext rc, String param) {
    return open(rc.pathParam(param));
  }

  /**
   * Opens the repository's bare origin at {@code <data-dir>/<repoId>/origin} — the same layout
   * {@code RepositoryService} clones into. Returns {@code null} (→ 404) for an id that isn't a
   * valid repo-id slug or whose origin doesn't exist; the caller closes the returned repo.
   */
  private Repository open(String repoId) {
    if (repoId == null || !repoId.matches(REPO_ID_PATTERN)) {
      return null;
    }
    Path origin = Path.of(dataDir, repoId, "origin");
    if (!Files.isDirectory(origin)) {
      return null;
    }
    try {
      return new FileRepositoryBuilder().setGitDir(origin.toFile()).setMustExist(true).build();
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Opens the repository addressed by {@code /git/:projectId/:repoName}: strips an optional {@code
   * .git} suffix, resolves {@code (projectId, name)} through the alias table to a repo id, then
   * opens that repo's on-disk origin. The path segments are only DB lookup keys (never filesystem
   * paths — the resolved id is), and the resolved id is re-validated by {@link #open(String)}, so
   * traversal is impossible. The lookup runs in its own transaction (this is a Vert.x worker thread
   * with no request context to bind a session from).
   */
  private Repository openByName(RoutingContext rc) {
    String projectId = rc.pathParam("projectId");
    String repoName = rc.pathParam("repoName");
    if (projectId == null || repoName == null) {
      return null;
    }
    String name =
        repoName.endsWith(".git") ? repoName.substring(0, repoName.length() - 4) : repoName;
    String repoId =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    repositoryNames
                        .findRepositoryByProjectAndName(projectId, name)
                        .map(repo -> repo.id)
                        .orElse(null));
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
