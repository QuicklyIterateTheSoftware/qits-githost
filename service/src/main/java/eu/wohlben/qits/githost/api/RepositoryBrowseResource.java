package eu.wohlben.qits.githost.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import eu.wohlben.qits.githost.GitRepositoryProvider;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.diff.RawText;
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
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jboss.logging.Logger;

/**
 * {@code GET /githost/api/repositories/{repoId}[/tree|/file]} — the browser plane's content reads,
 * for qits-spa-githost's per-repository Code pages.
 *
 * <p><b>Why this exists beside {@code /git/…/tree|blob}.</b> Those live on the git wire's storage
 * scheme, which {@code qits.githost.storage-client} is slated to close to qits-projects' own client
 * — a browser session holding {@code qits:admin} is exactly what it refuses. This surface is the
 * SPA's, sits under the service's gateway segment, and answers shapes a file browser wants: the
 * whole tree in one read (the SPA derives directories from paths, the same contract the workspace
 * daemon serves), and file content as a record that says {@code binary} instead of failing with a
 * 413. Unlike the anonymous catalogue beside it, these paths carry file contents and are therefore
 * role-gated — see the {@code githost-browse} policy in {@code application.properties}.
 *
 * <p><b>Addressing is the storage id</b>, the UUID qits-projects mints: the SPA resolves the public
 * {@code (project, repoName)} spelling to it client-side, and this host still holds no name for
 * anything. The {@code rev} is a query parameter rather than a path segment, so a slashy branch
 * name needs no percent-encoding dance.
 *
 * <p><b>A 404 says which thing is absent.</b> The {@code /git} reads answer a bare 404 for a
 * missing repository, revision and path alike, and every consumer has to re-read the root to tell
 * them apart ({@code GitHostReader.rootStatus} in qits-platform-maintenance). Here the body carries
 * {@code {"error": "no-such-repository" | "no-such-rev" | "no-such-path"}} so the page can say what
 * happened without a second request.
 *
 * <p><b>A failed read is a 5xx, never a 404 and never an empty answer</b> — the fe26a6c lesson,
 * spelled out on {@link RepositoriesResource}. Only {@link GitRepositoryProvider#open} returning
 * null is absence; everything a store throws becomes a 500 here.
 */
@Path("/repositories/{repoId}")
@Produces(MediaType.APPLICATION_JSON)
public class RepositoryBrowseResource {

  private static final Logger LOG = Logger.getLogger(RepositoryBrowseResource.class);

  /** The id rule the catalogue applies; an id outside it is served by no route on this host. */
  private static final String REPO_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9-]{0,63}";

  /** The rev rule {@code GitHostRoutes} applies to its own content reads. */
  private static final String REV_PATTERN = "[A-Za-z0-9][A-Za-z0-9._/-]{0,254}";

  private static final int MAX_PATH_LENGTH = 1024;

  /**
   * The largest file this resource answers with content. Past it the record still comes back, with
   * {@code binary: true} and the real {@code size} — the workspace daemon's soft degrade, chosen
   * over {@code /git/…/blob}'s hard 413 because the page renders "too large" as an answer, not an
   * error. The value matches the daemon's {@code FILE_CONTENT_CAP_BYTES}.
   */
  static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;

  @Inject GitRepositoryProvider repositories;

  /**
   * The repository as the browser plane spells it. {@code branches} empty means an empty repository
   * — a freshly provisioned origin has a HEAD naming an unborn branch and nothing else, and the
   * page reads the empty list as "nothing to browse yet" without ever asking for a tree.
   *
   * <p><b>Every record below carries {@code @RegisterForReflection}, and it is load-bearing.</b>
   * These entities travel inside an untyped {@link Response}, so the build-time analysis that
   * registers a typed signature's return ({@code RepositoriesResource}'s, for instance) never sees
   * them — and this service ships as a native image, where an unregistered entity is a 500 on
   * every answer, measured on the 2026.825.141202 deploy while the whole JVM-mode suite was green.
   */
  @RegisterForReflection
  public record DescribeResponse(String id, String defaultBranch, List<String> branches) {}

  /**
   * The whole tree at one commit: every blob's slash-separated path, in the walk's own order.
   * Directories are implied by the paths — the client derives them, the same contract the workspace
   * daemon's eager listing established. A submodule gitlink is skipped outright: it has no blob to
   * show and no tree to descend into on this host. {@code commitSha} is the resolved commit, the
   * natural generation token for anything the client caches.
   */
  @RegisterForReflection
  public record TreeResponse(String rev, String commitSha, List<String> paths) {}

  /**
   * One file. {@code binary} true covers both a genuinely binary blob and one past {@link
   * #MAX_CONTENT_BYTES}; {@code size} is always the blob's real size, so the page can say which. A
   * symlink answers its target string as content — that is what its blob holds.
   */
  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FileResponse(String path, boolean binary, long size, String content) {}

  @RegisterForReflection
  public record ErrorBody(String error) {}

  @GET
  public Response describe(@PathParam("repoId") String repoId) {
    if (!isValidRepoId(repoId)) {
      return badRequest("repoId must match " + REPO_ID_PATTERN);
    }
    try (Repository repo = openOrNull(repoId)) {
      if (repo == null) {
        return notFound("no-such-repository");
      }
      String defaultBranch = defaultBranchOf(repo);
      List<String> branches =
          repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS).stream()
              .map(ref -> ref.getName().substring(Constants.R_HEADS.length()))
              .sorted()
              .toList();
      return Response.ok(new DescribeResponse(repoId, defaultBranch, branches)).build();
    } catch (Exception e) {
      throw unavailable("could not describe repository " + repoId, e);
    }
  }

  @GET
  @Path("/tree")
  public Response tree(@PathParam("repoId") String repoId, @QueryParam("rev") String rev) {
    if (!isValidRepoId(repoId)) {
      return badRequest("repoId must match " + REPO_ID_PATTERN);
    }
    if (rev != null && !rev.isEmpty() && !isValidRev(rev)) {
      return badRequest("rev must match " + REV_PATTERN);
    }
    try (Repository repo = openOrNull(repoId)) {
      if (repo == null) {
        return notFound("no-such-repository");
      }
      String effectiveRev = rev == null || rev.isEmpty() ? defaultBranchOf(repo) : rev;
      try (RevWalk walk = new RevWalk(repo)) {
        RevCommit commit = resolveCommit(repo, walk, effectiveRev);
        if (commit == null) {
          return notFound("no-such-rev");
        }
        List<String> paths = new ArrayList<>();
        try (TreeWalk walker = new TreeWalk(repo)) {
          walker.addTree(commit.getTree());
          walker.setRecursive(true); // one walk, every blob; gitlinks are not recursed into
          while (walker.next()) {
            if (!FileMode.GITLINK.equals(walker.getFileMode(0))) {
              paths.add(walker.getPathString());
            }
          }
        }
        return Response.ok(new TreeResponse(effectiveRev, commit.name(), paths)).build();
      }
    } catch (MissingObjectException | IncorrectObjectTypeException e) {
      return notFound("no-such-rev");
    } catch (Exception e) {
      throw unavailable("could not list the tree of repository " + repoId, e);
    }
  }

  @GET
  @Path("/file")
  public Response file(
      @PathParam("repoId") String repoId,
      @QueryParam("rev") String rev,
      @QueryParam("path") String path) {
    if (!isValidRepoId(repoId)) {
      return badRequest("repoId must match " + REPO_ID_PATTERN);
    }
    if (rev != null && !rev.isEmpty() && !isValidRev(rev)) {
      return badRequest("rev must match " + REV_PATTERN);
    }
    if (path == null || path.isEmpty() || !isValidPath(path)) {
      return badRequest("path must be a repository-relative file path");
    }
    try (Repository repo = openOrNull(repoId)) {
      if (repo == null) {
        return notFound("no-such-repository");
      }
      String effectiveRev = rev == null || rev.isEmpty() ? defaultBranchOf(repo) : rev;
      try (RevWalk walk = new RevWalk(repo)) {
        RevCommit commit = resolveCommit(repo, walk, effectiveRev);
        if (commit == null) {
          return notFound("no-such-rev");
        }
        try (TreeWalk found = TreeWalk.forPath(repo, path, commit.getTree())) {
          if (found == null || !isReadable(found.getFileMode(0))) {
            return notFound("no-such-path");
          }
          ObjectLoader loader = repo.open(found.getObjectId(0), Constants.OBJ_BLOB);
          long size = loader.getSize();
          if (size > MAX_CONTENT_BYTES) {
            return Response.ok(new FileResponse(path, true, size, null)).build();
          }
          byte[] bytes = loader.getBytes(MAX_CONTENT_BYTES);
          if (RawText.isBinary(bytes, bytes.length, true)) {
            return Response.ok(new FileResponse(path, true, size, null)).build();
          }
          return Response.ok(
                  new FileResponse(path, false, size, new String(bytes, StandardCharsets.UTF_8)))
              .build();
        }
      }
    } catch (MissingObjectException | IncorrectObjectTypeException e) {
      return notFound("no-such-rev");
    } catch (Exception e) {
      throw unavailable("could not read a file of repository " + repoId, e);
    }
  }

  /** Null is absence and only absence; a store that cannot answer throws out of here unchanged. */
  private Repository openOrNull(String repoId) {
    return repositories.open(repoId);
  }

  /**
   * The commit {@code rev} names, or null. Same contract as the {@code /git} reads: anything the
   * repository holds resolves, reachable from a ref or not; an annotated tag is peeled.
   */
  private static RevCommit resolveCommit(Repository repo, RevWalk walk, String rev)
      throws IOException {
    if (rev == null) {
      return null; // an unborn HEAD has no branch name to resolve
    }
    ObjectId id;
    try {
      id = repo.resolve(rev);
    } catch (RevisionSyntaxException e) {
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

  private static String defaultBranchOf(Repository repo) throws IOException {
    String full = repo.getFullBranch();
    return full != null && full.startsWith(Constants.R_HEADS)
        ? full.substring(Constants.R_HEADS.length())
        : full;
  }

  /** A file the viewer can show: a regular blob, an executable one, or a symlink's target. */
  private static boolean isReadable(FileMode mode) {
    return FileMode.REGULAR_FILE.equals(mode)
        || FileMode.EXECUTABLE_FILE.equals(mode)
        || FileMode.SYMLINK.equals(mode);
  }

  private static boolean isValidRepoId(String repoId) {
    return repoId != null && repoId.matches(REPO_ID_PATTERN);
  }

  private static boolean isValidRev(String rev) {
    return rev != null && !rev.contains("..") && rev.matches(REV_PATTERN);
  }

  private static boolean isValidPath(String path) {
    if (path.length() > MAX_PATH_LENGTH) {
      return false;
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

  private static Response badRequest(String message) {
    return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorBody(message)).build();
  }

  private static Response notFound(String code) {
    return Response.status(Response.Status.NOT_FOUND).entity(new ErrorBody(code)).build();
  }

  private ServerErrorException unavailable(String what, Exception cause) {
    LOG.error(what, cause);
    return new ServerErrorException(what, Response.Status.INTERNAL_SERVER_ERROR, cause);
  }
}
