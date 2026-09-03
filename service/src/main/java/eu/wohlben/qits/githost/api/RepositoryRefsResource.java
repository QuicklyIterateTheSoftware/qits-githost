package eu.wohlben.qits.githost.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import eu.wohlben.qits.githost.GitIdentity;
import eu.wohlben.qits.githost.GitRepositoryProvider;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jboss.logging.Logger;

/**
 * {@code POST /githost/api/repositories/{repoId}/merges} — the git primitives the platform's
 * release flow is orchestrated out of, and the first writes this host performs that are not a push.
 *
 * <h2>Primitives, not a release flow</h2>
 *
 * <p>Everything here speaks <b>refs, shas and paths</b>. There is no "release" in any name, no
 * version, no branch naming convention, no notion of what a {@code release/…} ref is for: the
 * caller (qits-projects) owns that vocabulary and this host owns the git. A primitive that knew
 * what it was being used for would have to be changed the next time the flow changed, and this
 * service exists precisely so that it does not.
 *
 * <h2>In-core, against the bare</h2>
 *
 * <p>No worktree, no clone, no checkout — a {@code QitsDfsRepository} has no directory to put one
 * in, and the git CLI cannot open it at all. Every operation is JGit in-process: the merge is
 * {@link MergeStrategy#RECURSIVE}'s in-core merger, the objects go through one {@link
 * ObjectInserter}, and the ref moves through the reftable database. Nothing touches a disk that is
 * not the blob store.
 *
 * <h2>These writes do not fire post-receive</h2>
 *
 * <p><b>Stated because it inverts a property this storage was built for.</b> {@code
 * QitsDfsRepository}'s javadoc says receive-pack is the only writer, so nothing changes a ref
 * without firing {@code post-receive} — and therefore without publishing {@code SCMPublishCommit}
 * and friends. These endpoints are a second writer, and they publish <b>nothing</b>. That is the
 * design and not an oversight: the caller is a domain service that is already narrating what it is
 * doing (a release request changed, a release happened), and an {@code SCMPublishCommit} for every
 * intermediate merge of a release request would announce steps nobody outside qits-projects has any
 * business reacting to. A consumer that wants to know a release happened listens for {@code
 * SCMRelease}, which its publisher emits.
 *
 * <h2>The guard</h2>
 *
 * <p>{@link #MACHINE_ROLE} and nothing else. The {@code githost-browse} HTTP policy already stands
 * in front of every path under {@code /githost/api/repositories/*} and answers an anonymous caller
 * with a 401; the annotation narrows what passes it to a <b>machine</b>. {@code qits:admin} — a
 * browser session, through the gateway's forwarded headers — is deliberately not enough: these are
 * primitives an orchestrator composes, and a half-composed release run from a browser tab is not a
 * thing anyone should be able to do by accident.
 *
 * <h2>Native image</h2>
 *
 * <p>Every record below carries {@code @RegisterForReflection}, requests included. The responses
 * travel inside an untyped {@link Response}, so no build-time analysis of a method signature ever
 * sees them, and this service ships as a GraalVM binary where an unregistered entity is a 500 on
 * every answer while the whole JVM suite stays green (measured on the 2026.825.141202 deploy).
 */
@Path("/repositories/{repoId}")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(RepositoryRefsResource.MACHINE_ROLE)
public class RepositoryRefsResource {

  private static final Logger LOG = Logger.getLogger(RepositoryRefsResource.class);

  /**
   * The machine role, spelled as a constant because {@code @RolesAllowed} needs one. It is the role
   * a platform service's validated bearer carries; a person's session carries {@code qits:admin}
   * and is refused here.
   */
  static final String MACHINE_ROLE = "qits:system";

  /** The id rule the rest of this API applies; an id outside it is served by no route on this host. */
  private static final String REPO_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9-]{0,63}";

  /**
   * What a source may look like: a full ref name, a short branch or tag name, or a sha. The same
   * discipline the content reads apply — no leading dash or dot, no {@code ..}, no whitespace, and
   * none of {@code ^~@{}:}, which keeps a source a NAME rather than a revision expression {@link
   * Repository#resolve} would happily evaluate.
   */
  private static final String REV_PATTERN = "[A-Za-z0-9][A-Za-z0-9._/-]{0,254}";

  /** The most heads one merge will fold. A bound, not a policy — it keeps a runaway body cheap. */
  private static final int MAX_SOURCES = 64;

  @Inject GitRepositoryProvider repositories;

  @Inject GitIdentity identity;

  /** An author a caller named for itself. Both halves or neither — see {@link GitIdentity}. */
  @RegisterForReflection
  public record Author(String name, String email) {}

  /**
   * Fold {@code sources} into {@code target}.
   *
   * <p>{@code target} is a full {@code refs/heads/…} name; {@code sources} are refs, tags or shas,
   * in the order they should become parents. {@code message} and {@code author} are optional.
   */
  @RegisterForReflection
  public record MergeRequest(
      String target, List<String> sources, String message, Author author) {}

  /**
   * What the target ref now is.
   *
   * <p>{@code outcome} is one of {@code merged} (a new merge commit was created and the ref moved
   * to it), {@code fast-forward} (the ref was created at, or moved onto, an existing commit — no
   * commit was created) or {@code unchanged} (the ref already said the right thing and did not
   * move). {@code parents} are the parents of the commit {@code sha} names, so a caller can see the
   * octopus it asked for; {@code skipped} names the sources that contributed nothing because
   * another head already contained them.
   */
  @RegisterForReflection
  public record MergeResponse(
      String target, String sha, String outcome, List<String> parents, List<String> skipped) {}

  /**
   * A conflict, reported rather than resolved. {@code head} is the source <b>as the caller spelled
   * it</b> — the head being folded in when the conflict appeared, which is git's own answer to
   * "who broke it" and the one the caller can act on.
   */
  @RegisterForReflection
  public record ConflictedPath(String path, String head, String headSha, String reason) {}

  /** The body of the 409 a conflicted merge answers. {@code error} is always {@code merge-conflict}. */
  @RegisterForReflection
  public record MergeConflictResponse(
      String error, String target, List<ConflictedPath> conflicts) {}

  /**
   * The house error shape, with room for the thing that was wrong. {@code error} is a code a caller
   * branches on; {@code detail} is for a human reading a log.
   */
  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ErrorBody(String error, String detail) {}

  /** One head of the fold: the commit, and the spelling the caller (or this class) named it by. */
  private record Head(String spelling, RevCommit commit) {}

  /**
   * The in-core octopus merge.
   *
   * <p><b>The target's own tip is the first head.</b> That is what makes this git's octopus and not
   * an invention: {@code git merge A B C} folds A, B and C onto {@code HEAD}, so the commit it
   * writes has four parents and the branch it was run on is the first of them. Here the target ref
   * plays HEAD — it is a head when it exists, and simply absent when the ref does not, which is
   * what makes the first call on a fresh {@code release/<id>} produce a plain N-parent octopus of
   * the sources alone.
   *
   * <p><b>A head another head already contains is dropped</b>, exactly as {@code git merge} answers
   * "Already up to date" for one. Two consequences fall out of that single rule, and they are the
   * whole of this endpoint's idempotency:
   *
   * <ul>
   *   <li><b>Nothing changed since the last merge → no new commit.</b> Every source is a parent of
   *       the target's tip, so every source is contained in it, so every source is dropped and the
   *       target is the only head left — which is the {@code unchanged} answer, with the same sha
   *       the previous call returned. Re-running a merge is free and leaves no garbage.
   *   <li><b>One effective head → no empty octopus.</b> If the survivor is the target, nothing
   *       moves; if it is a source (because the target is an ancestor of it), the ref fast-forwards
   *       onto it. A one-parent "merge" commit is never written.
   * </ul>
   *
   * <p><b>The fold is pairwise</b>, which is how git's own octopus strategy works: heads are merged
   * into an accumulator two at a time, and only the tree of the last fold is kept — the final
   * commit carries <b>all</b> the heads as parents, not a chain of two-parent merges. The
   * intermediate accumulators are real commits, written into the inserter so that the next fold's
   * merge base can be computed against the history merged so far (JGit's recursive merger does the
   * same thing for a criss-cross base). They are never referenced by any ref; on a conflict the
   * inserter is discarded without a flush and not one byte of any of it lands.
   *
   * <p><b>On a conflict no ref moves</b> and the answer is a 409 naming the paths and the head that
   * introduced each. Resolution is not this host's business — it has no worktree to resolve in and
   * no opinion about who should win.
   *
   * <p><b>Nothing here protects the target.</b> Merging into a repository's default branch is
   * allowed and is a designed use of this endpoint (a release reaches {@code main} only after it
   * deployed, and it reaches it through here). {@code ProtectedRefHook} guards the <i>push</i> door
   * against reflex; this door is only reachable by a machine that was asked for by name.
   */
  @POST
  @Path("/merges")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response merge(@PathParam("repoId") String repoId, MergeRequest request) {
    if (!isValidRepoId(repoId)) {
      return badRequest("repoId must match " + REPO_ID_PATTERN);
    }
    if (request == null) {
      return badRequest("a merge needs a body");
    }
    if (!isBranchRef(request.target())) {
      return badRequest("target must be a valid refs/heads/… ref name");
    }
    List<String> sources = request.sources() == null ? List.of() : request.sources();
    if (sources.isEmpty()) {
      return badRequest("sources must name at least one ref, tag or sha");
    }
    if (sources.size() > MAX_SOURCES) {
      return badRequest("at most " + MAX_SOURCES + " sources");
    }
    for (String source : sources) {
      if (!isValidRev(source)) {
        return badRequest("source must match " + REV_PATTERN + ": " + source);
      }
    }

    try (Repository repo = repositories.open(repoId)) {
      if (repo == null) {
        return notFound("no-such-repository", repoId);
      }
      return fold(repo, request, sources);
    } catch (Exception e) {
      throw unavailable("could not merge into " + request.target() + " of repository " + repoId, e);
    }
  }

  /** The merge itself, on an open repository. Everything it inserts rides one {@link ObjectInserter}. */
  private Response fold(Repository repo, MergeRequest request, List<String> sources)
      throws IOException {
    String target = request.target();
    try (ObjectInserter inserter = repo.newObjectInserter();
        ObjectReader reader = inserter.newReader();
        RevWalk walk = new RevWalk(reader)) {

      Ref targetRef = repo.getRefDatabase().exactRef(target);
      ObjectId targetOld = targetRef == null ? null : targetRef.getObjectId();

      // HEAD first, exactly as git octopus orders its parents.
      List<Head> heads = new ArrayList<>();
      Set<ObjectId> seen = new LinkedHashSet<>();
      if (targetOld != null) {
        heads.add(new Head(target, walk.parseCommit(targetOld)));
        seen.add(targetOld.toObjectId());
      }
      for (String source : sources) {
        ObjectId id;
        try {
          id = repo.resolve(source);
        } catch (RevisionSyntaxException e) {
          return badRequest("source is not a ref, tag or sha: " + source);
        }
        if (id == null) {
          return notFound("no-such-source", source);
        }
        RevCommit commit;
        try {
          commit = walk.parseCommit(id);
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
          return badRequest("source does not name a commit: " + source);
        }
        // A source naming a commit another source already named is one head, spelled twice.
        if (seen.add(commit.toObjectId())) {
          heads.add(new Head(source, commit));
        }
      }

      List<Head> effective = new ArrayList<>();
      List<String> skipped = new ArrayList<>();
      for (Head head : heads) {
        if (containedInAnother(walk, head, heads)) {
          if (!head.spelling().equals(target)) {
            skipped.add(head.spelling());
          }
          continue;
        }
        effective.add(head);
      }

      if (effective.size() == 1) {
        Head only = effective.get(0);
        if (only.commit().equals(targetOld)) {
          return Response.ok(
                  new MergeResponse(
                      target,
                      only.commit().name(),
                      "unchanged",
                      parentNames(only.commit()),
                      skipped))
              .build();
        }
        // The target is absent, or an ancestor of the one surviving head: move the ref onto it
        // rather than writing a merge commit with a single parent.
        Response moved = moveRef(repo, target, targetOld, only.commit(), "fast-forward: " + only.spelling());
        return moved != null
            ? moved
            : Response.ok(
                    new MergeResponse(
                        target,
                        only.commit().name(),
                        "fast-forward",
                        parentNames(only.commit()),
                        skipped))
                .build();
      }

      PersonIdent person = authorOf(request.author());
      RevCommit accumulator = effective.get(0).commit();
      ObjectId resultTree = null;
      for (int i = 1; i < effective.size(); i++) {
        Head next = effective.get(i);
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RECURSIVE.newMerger(repo, true);
        // The shared inserter, so every fold reads what the previous one wrote and nothing is
        // flushed until the whole octopus succeeded. It closes the merger's own inserter for us.
        merger.setObjectInserter(inserter);
        merger.setCommitNames(new String[] {"BASE", target, next.spelling()});
        if (!merger.merge(false, accumulator, next.commit())) {
          return conflict(target, merger, next);
        }
        ObjectId folded = merger.getResultTreeId();
        if (i == effective.size() - 1) {
          resultTree = folded;
        } else {
          // An accumulator the next fold can compute a merge base against. Unreferenced by
          // construction, and discarded with the inserter if a later fold conflicts.
          accumulator =
              walk.parseCommit(
                  insertCommit(
                      inserter,
                      folded,
                      List.of(accumulator, next.commit()),
                      person,
                      "octopus fold onto " + target));
        }
      }

      List<ObjectId> parents = effective.stream().map(head -> (ObjectId) head.commit()).toList();
      ObjectId merged =
          insertCommit(inserter, resultTree, parents, person, mergeMessage(request, effective));
      inserter.flush();

      Response moved = moveRef(repo, target, targetOld, merged, "octopus merge");
      if (moved != null) {
        return moved;
      }
      return Response.ok(
              new MergeResponse(
                  target,
                  merged.name(),
                  "merged",
                  parents.stream().map(ObjectId::name).toList(),
                  skipped))
          .build();
    }
  }

  /** Whether some other head already contains this one, which is what makes it nothing to merge. */
  private static boolean containedInAnother(RevWalk walk, Head head, List<Head> heads)
      throws IOException {
    for (Head other : heads) {
      if (other.commit().equals(head.commit())) {
        continue;
      }
      if (walk.isMergedInto(head.commit(), other.commit())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The conflict report: every unmerged path, plus anything the merger could not even attempt,
   * attributed to the head being folded in when it happened.
   */
  private static Response conflict(String target, ResolveMerger merger, Head head) {
    Set<String> paths = new TreeSet<>(merger.getUnmergedPaths());
    Map<String, ResolveMerger.MergeFailureReason> failing = merger.getFailingPaths();
    List<ConflictedPath> conflicts = new ArrayList<>();
    for (String path : paths) {
      conflicts.add(new ConflictedPath(path, head.spelling(), head.commit().name(), "content"));
    }
    if (failing != null) {
      failing.forEach(
          (path, reason) -> {
            if (!paths.contains(path)) {
              conflicts.add(
                  new ConflictedPath(
                      path, head.spelling(), head.commit().name(), reason.name().toLowerCase()));
            }
          });
    }
    return Response.status(Response.Status.CONFLICT)
        .entity(new MergeConflictResponse("merge-conflict", target, conflicts))
        .build();
  }

  /**
   * Moves the ref, or answers the refusal. Returns {@code null} when the update landed — the caller
   * then builds its own success body.
   *
   * <p>The update is a compare-and-swap against what this request read, so a ref that moved under
   * a concurrent caller is a 409 rather than a silent overwrite. {@code setForceUpdate} is on
   * because a re-merge legitimately rewrites the target: the octopus is rebuilt from the heads, and
   * the caller owns the ref it named.
   */
  private static Response moveRef(
      Repository repo, String ref, ObjectId expectedOld, ObjectId to, String reflog)
      throws IOException {
    RefUpdate update = repo.updateRef(ref);
    update.setNewObjectId(to);
    update.setExpectedOldObjectId(expectedOld == null ? ObjectId.zeroId() : expectedOld);
    update.setForceUpdate(true);
    update.setRefLogMessage("qits-githost: " + reflog, false);
    RefUpdate.Result result = update.update();
    return switch (result) {
      case NEW, FORCED, FAST_FORWARD, NO_CHANGE -> null;
      case LOCK_FAILURE, REJECTED, REJECTED_CURRENT_BRANCH, REJECTED_OTHER_REASON ->
          Response.status(Response.Status.CONFLICT)
              .entity(new ErrorBody("ref-moved", ref + ": " + result.name()))
              .build();
      default ->
          throw new ServerErrorException(
              "could not update " + ref + ": " + result.name(),
              Response.Status.INTERNAL_SERVER_ERROR);
    };
  }

  private static ObjectId insertCommit(
      ObjectInserter inserter,
      ObjectId tree,
      List<ObjectId> parents,
      PersonIdent person,
      String message)
      throws IOException {
    CommitBuilder builder = new CommitBuilder();
    builder.setTreeId(tree);
    builder.setParentIds(parents);
    builder.setAuthor(person);
    builder.setCommitter(person);
    builder.setMessage(message.endsWith("\n") ? message : message + "\n");
    return inserter.insert(builder);
  }

  /** The caller's message, or one that says what was folded — never a release word. */
  private static String mergeMessage(MergeRequest request, List<Head> heads) {
    if (request.message() != null && !request.message().isBlank()) {
      return request.message();
    }
    List<String> names = heads.stream().skip(1).map(Head::spelling).toList();
    return "Merge " + String.join(", ", names) + " into " + request.target();
  }

  private PersonIdent authorOf(Author author) {
    return author == null ? identity.person() : identity.person(author.name(), author.email());
  }

  private static List<String> parentNames(RevCommit commit) {
    List<String> names = new ArrayList<>(commit.getParentCount());
    for (RevCommit parent : commit.getParents()) {
      names.add(parent.name());
    }
    return names;
  }

  static boolean isValidRepoId(String repoId) {
    return repoId != null && repoId.matches(REPO_ID_PATTERN);
  }

  static boolean isValidRev(String rev) {
    return rev != null && !rev.contains("..") && rev.matches(REV_PATTERN);
  }

  /** A full branch ref name, and one git itself would accept. */
  static boolean isBranchRef(String ref) {
    return ref != null
        && ref.startsWith(Constants.R_HEADS)
        && ref.length() > Constants.R_HEADS.length()
        && Repository.isValidRefName(ref);
  }

  static Response badRequest(String message) {
    return Response.status(Response.Status.BAD_REQUEST)
        .entity(new ErrorBody("bad-request", message))
        .build();
  }

  static Response notFound(String code, String detail) {
    return Response.status(Response.Status.NOT_FOUND).entity(new ErrorBody(code, detail)).build();
  }

  /**
   * The fe26a6c rule, one surface further out: a read or a write this host could not make is a 500
   * with the cause logged, never an answer that reads as absence.
   */
  ServerErrorException unavailable(String what, Exception cause) {
    if (cause instanceof ServerErrorException server) {
      return server;
    }
    LOG.error(what, cause);
    return new ServerErrorException(what, Response.Status.INTERNAL_SERVER_ERROR, cause);
  }
}
