package eu.wohlben.qits.githost.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import eu.wohlben.qits.githost.GitIdentity;
import eu.wohlben.qits.githost.GitRepositoryProvider;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jboss.logging.Logger;

/**
 * {@code POST /githost/api/repositories/{repoId}/{merges|tags|commits}} and {@code DELETE
 * …/branches/{name}} — the git primitives the platform's release flow is orchestrated out of, and
 * the first writes this host performs that are not a push.
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

  /**
   * What one commit-onto-ref will write. Bounds rather than policy, for the same reason {@code
   * GitHostRoutes.MAX_BLOB_BYTES} is a constant and not a key: this endpoint exists for manifests —
   * poms, package.jsons, a lockfile — and a caller with a repository's worth of bytes to write
   * pushes them.
   */
  private static final int MAX_FILES = 512;

  private static final int MAX_FILE_BYTES = 1024 * 1024;

  private static final int MAX_PATH_LENGTH = 1024;

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
   * An annotated tag at {@code sha}. {@code name} is a tag name ({@code 2026.903.120000}) or the
   * full ref ({@code refs/tags/2026.903.120000}); {@code sha} is a ref, tag or sha naming the
   * commit to tag. {@code message} defaults to the tag's own name.
   */
  @RegisterForReflection
  public record TagRequest(String name, String sha, String message, Author author) {}

  /**
   * The created tag. {@code sha} is the <b>tag object</b> — this host only makes annotated tags, so
   * the ref and the commit are never the same id — and {@code object} is the commit it points at.
   */
  @RegisterForReflection
  public record TagResponse(String tag, String sha, String object) {}

  /**
   * The refusal a tag that already exists gets, and the whole point of the endpoint: {@code error}
   * is {@code tag-exists} and {@code sha} is what the ref already says, so a caller that is using
   * tag creation as its uniqueness guarantee learns both facts from one answer.
   */
  @RegisterForReflection
  public record TagExistsResponse(String error, String tag, String sha) {}

  /**
   * One commit's worth of file changes on a branch ref. {@code files} is path → UTF-8 content,
   * {@code deletePaths} is paths to remove; at least one of the two must say something. The content
   * is the caller's — this host writes it and reads none of it.
   */
  @RegisterForReflection
  public record CommitRequest(
      String ref,
      String message,
      Map<String, String> files,
      List<String> deletePaths,
      Author author) {}

  /**
   * What the ref now is. {@code outcome} is {@code committed} (a commit was written and the ref
   * fast-forwarded onto it) or {@code unchanged} (the edits produced the tip's own tree, so there
   * was nothing to commit and the ref did not move).
   */
  @RegisterForReflection
  public record CommitResponse(String ref, String sha, String parent, String outcome) {}

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

  /**
   * The annotated tag, and the platform's version-uniqueness guarantee.
   *
   * <p><b>An existing tag ref is refused with an answer of its own</b> — 409 and {@code
   * {"error": "tag-exists", …}} — and that distinction is the contract, not a nicety. It replaces
   * the atomic branch-and-tag push the workspaces release door used to rely on: a caller stamps a
   * version, asks for the tag, and a 409 means "somebody already released that version, stamp
   * another one". Anything else that goes wrong answers differently, so the caller can tell a taken
   * name from a broken host.
   *
   * <p>The race is refused the same way. The ref is created with an expected-old of zero, so two
   * callers asking for one name at once produce one tag and one {@code tag-exists} — the check is
   * not a look-before-you-leap that a second process can slip through.
   *
   * <p>Always <b>annotated</b>: a tag object with a tagger, a time and a message, so a released
   * version carries who made it and when. A lightweight tag would be a ref this endpoint could not
   * tell from a branch tip.
   */
  @POST
  @Path("/tags")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response tag(@PathParam("repoId") String repoId, TagRequest request) {
    if (!isValidRepoId(repoId)) {
      return badRequest("repoId must match " + REPO_ID_PATTERN);
    }
    if (request == null) {
      return badRequest("a tag needs a body");
    }
    String tagRef = tagRef(request.name());
    if (tagRef == null) {
      return badRequest("name must be a valid tag name or a refs/tags/… ref name");
    }
    if (!isValidRev(request.sha())) {
      return badRequest("sha must match " + REV_PATTERN);
    }

    try (Repository repo = repositories.open(repoId)) {
      if (repo == null) {
        return notFound("no-such-repository", repoId);
      }
      Ref existing = repo.getRefDatabase().exactRef(tagRef);
      if (existing != null) {
        return tagExists(tagRef, existing);
      }
      try (ObjectInserter inserter = repo.newObjectInserter();
          ObjectReader reader = inserter.newReader();
          RevWalk walk = new RevWalk(reader)) {
        ObjectId id;
        try {
          id = repo.resolve(request.sha());
        } catch (RevisionSyntaxException e) {
          return badRequest("sha is not a ref, tag or sha: " + request.sha());
        }
        if (id == null) {
          return notFound("no-such-object", request.sha());
        }
        RevCommit commit;
        try {
          commit = walk.parseCommit(id);
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
          return badRequest("sha does not name a commit: " + request.sha());
        }
        String name = tagRef.substring(Constants.R_TAGS.length());
        TagBuilder builder = new TagBuilder();
        builder.setTag(name);
        builder.setObjectId(commit);
        builder.setTagger(authorOf(request.author()));
        builder.setMessage(tagMessage(request, name));
        ObjectId tagId = inserter.insert(builder);
        inserter.flush();

        Response refused = moveRef(repo, tagRef, null, tagId, "tag " + name);
        if (refused != null) {
          // Lost the race between the read above and this update: the other caller's tag is the
          // one that exists, and this caller must learn that rather than a generic conflict.
          Ref raced = repo.getRefDatabase().exactRef(tagRef);
          return raced != null ? tagExists(tagRef, raced) : refused;
        }
        return Response.status(Response.Status.CREATED)
            .entity(new TagResponse(tagRef, tagId.name(), commit.name()))
            .build();
      }
    } catch (Exception e) {
      throw unavailable("could not tag " + tagRef + " in repository " + repoId, e);
    }
  }

  /**
   * One commit's worth of files onto a branch ref, in-core: read the tip's tree, apply the edits,
   * write the new tree, commit it onto the tip and fast-forward the ref.
   *
   * <p><b>The content is the caller's and this host reads none of it.</b> The commit that stamps a
   * manifest version is qits-projects' work — it knows what a pom and a package.json are; here they
   * are paths and bytes. That is the whole reason this primitive is a map rather than an operation.
   *
   * <p><b>An edit that changes nothing writes nothing.</b> If the built tree is the tip's own tree,
   * the answer is {@code unchanged} with the tip's sha and the ref does not move — so a retried
   * bump after a timeout is free rather than a second empty commit.
   *
   * <p>The ref moves as a compare-and-swap against the tip this request read, so a branch that moved
   * underneath a slow caller is a 409 rather than a lost commit.
   */
  @POST
  @Path("/commits")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response commit(@PathParam("repoId") String repoId, CommitRequest request) {
    if (!isValidRepoId(repoId)) {
      return badRequest("repoId must match " + REPO_ID_PATTERN);
    }
    if (request == null) {
      return badRequest("a commit needs a body");
    }
    if (!isBranchRef(request.ref())) {
      return badRequest("ref must be a valid refs/heads/… ref name");
    }
    Map<String, String> files = request.files() == null ? Map.of() : request.files();
    List<String> deletions = request.deletePaths() == null ? List.of() : request.deletePaths();
    if (files.isEmpty() && deletions.isEmpty()) {
      return badRequest("a commit must write or delete at least one path");
    }
    if (files.size() + deletions.size() > MAX_FILES) {
      return badRequest("at most " + MAX_FILES + " paths in one commit");
    }
    for (String path : files.keySet()) {
      if (!isValidPath(path)) {
        return badRequest("not a repository-relative file path: " + path);
      }
      String content = files.get(path);
      if (content == null) {
        return badRequest("no content for " + path + " (a deletion goes in deletePaths)");
      }
      if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
        return badRequest(path + " is larger than " + MAX_FILE_BYTES + " bytes");
      }
    }
    for (String path : deletions) {
      if (!isValidPath(path)) {
        return badRequest("not a repository-relative file path: " + path);
      }
    }
    if (request.message() == null || request.message().isBlank()) {
      return badRequest("a commit needs a message");
    }

    try (Repository repo = repositories.open(repoId)) {
      if (repo == null) {
        return notFound("no-such-repository", repoId);
      }
      Ref branch = repo.getRefDatabase().exactRef(request.ref());
      if (branch == null || branch.getObjectId() == null) {
        return notFound("no-such-ref", request.ref());
      }
      ObjectId tip = branch.getObjectId();
      try (ObjectInserter inserter = repo.newObjectInserter();
          ObjectReader reader = inserter.newReader();
          RevWalk walk = new RevWalk(reader)) {
        RevCommit parent = walk.parseCommit(tip);
        ObjectId tree = editedTree(inserter, reader, parent, files, deletions);
        if (tree.equals(parent.getTree())) {
          return Response.ok(
                  new CommitResponse(request.ref(), parent.name(), parent.name(), "unchanged"))
              .build();
        }
        ObjectId committed =
            insertCommit(
                inserter,
                tree,
                List.of(tip),
                authorOf(request.author()),
                request.message());
        inserter.flush();
        Response refused = moveRef(repo, request.ref(), tip, committed, "commit onto " + request.ref());
        if (refused != null) {
          return refused;
        }
        return Response.ok(
                new CommitResponse(request.ref(), committed.name(), parent.name(), "committed"))
            .build();
      }
    } catch (Exception e) {
      throw unavailable("could not commit onto " + request.ref() + " of repository " + repoId, e);
    }
  }

  /**
   * Deletes a branch ref — a release request's backing branch and the source branches it consumed,
   * in the flow this exists for.
   *
   * <p><b>The repository's default branch is refused, always.</b> {@code ProtectedRefHook} guards
   * the same ref on the push door, and this door has to guard it too or the seatbelt would have a
   * hole shaped like an HTTP call. It is refused <b>unconditionally</b> rather than under that
   * hook's {@code protect-default-branch} switch, and the difference is deliberate: the switch
   * ships off because this host serves its own redeploy and a protection bug must not be able to
   * reject the push that fixes it — an argument about pushes, which nothing here is. No caller of a
   * ref primitive has a reason to delete a repository's default branch, and one that wants the
   * repository gone deletes the repository ({@code DELETE /git/:repoId}).
   *
   * <p>The name is the tail of the path, so a slashy branch needs no encoding dance: {@code DELETE
   * …/branches/release/17} and {@code …/branches/refs/heads/release/17} both name the same ref.
   */
  @DELETE
  @Path("/branches/{name:.+}")
  public Response deleteBranch(
      @PathParam("repoId") String repoId, @PathParam("name") String name) {
    if (!isValidRepoId(repoId)) {
      return badRequest("repoId must match " + REPO_ID_PATTERN);
    }
    String ref = branchRef(name);
    if (ref == null) {
      return badRequest("name must be a valid branch name or a refs/heads/… ref name");
    }
    try (Repository repo = repositories.open(repoId)) {
      if (repo == null) {
        return notFound("no-such-repository", repoId);
      }
      if (ref.equals(repo.getFullBranch())) {
        return Response.status(Response.Status.CONFLICT)
            .entity(new ErrorBody("protected-branch", ref + " is this repository's default branch"))
            .build();
      }
      Ref branch = repo.getRefDatabase().exactRef(ref);
      if (branch == null) {
        return notFound("no-such-branch", ref);
      }
      RefUpdate update = repo.updateRef(ref);
      update.setExpectedOldObjectId(branch.getObjectId());
      update.setForceUpdate(true);
      update.setRefLogMessage("qits-githost: delete " + ref, false);
      RefUpdate.Result result = update.delete();
      return switch (result) {
        case FORCED, NEW, NO_CHANGE -> Response.noContent().build();
        case LOCK_FAILURE, REJECTED, REJECTED_CURRENT_BRANCH, REJECTED_OTHER_REASON ->
            Response.status(Response.Status.CONFLICT)
                .entity(new ErrorBody("ref-moved", ref + ": " + result.name()))
                .build();
        default ->
            throw new ServerErrorException(
                "could not delete " + ref + ": " + result.name(),
                Response.Status.INTERNAL_SERVER_ERROR);
      };
    } catch (Exception e) {
      throw unavailable("could not delete " + ref + " of repository " + repoId, e);
    }
  }

  /**
   * The tip's tree with the caller's writes and deletions applied, built in an in-core {@link
   * DirCache} — the standard JGit recipe for making a tree without a worktree.
   *
   * <p>An existing path keeps an executable bit it already had; anything else becomes a regular
   * file. A deletion of a path the tree does not hold is not an error: the request describes the
   * tree the caller wants, and it already has it.
   */
  private static ObjectId editedTree(
      ObjectInserter inserter,
      ObjectReader reader,
      RevCommit parent,
      Map<String, String> files,
      List<String> deletions)
      throws IOException {
    DirCache cache = DirCache.newInCore();
    DirCacheBuilder from = cache.builder();
    from.addTree(new byte[0], DirCacheEntry.STAGE_0, reader, parent.getTree());
    from.finish();

    DirCacheEditor editor = cache.editor();
    for (Map.Entry<String, String> file : files.entrySet()) {
      ObjectId blob =
          inserter.insert(Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8));
      editor.add(
          new DirCacheEditor.PathEdit(file.getKey()) {
            @Override
            public void apply(DirCacheEntry entry) {
              if (entry.getRawMode() != FileMode.EXECUTABLE_FILE.getBits()) {
                entry.setFileMode(FileMode.REGULAR_FILE);
              }
              entry.setObjectId(blob);
            }
          });
    }
    for (String path : deletions) {
      editor.add(new DirCacheEditor.DeletePath(path));
    }
    editor.finish();
    return cache.writeTree(inserter);
  }

  private static Response tagExists(String tagRef, Ref existing) {
    return Response.status(Response.Status.CONFLICT)
        .entity(
            new TagExistsResponse(
                "tag-exists",
                tagRef,
                existing.getObjectId() == null ? null : existing.getObjectId().name()))
        .build();
  }

  private static String tagMessage(TagRequest request, String name) {
    String message = request.message();
    if (message == null || message.isBlank()) {
      message = name;
    }
    return message.endsWith("\n") ? message : message + "\n";
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

  /**
   * The full ref for a branch the caller named either way, or {@code null} if it is not one. Both
   * spellings are accepted because both are natural at a call site: the merge target is a full ref
   * because it may not exist yet, and a branch being deleted is usually held as a bare name.
   */
  static String branchRef(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String ref = name.startsWith(Constants.R_HEADS) ? name : Constants.R_HEADS + name;
    return isBranchRef(ref) ? ref : null;
  }

  /** The same, for a tag. */
  static String tagRef(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String ref = name.startsWith(Constants.R_TAGS) ? name : Constants.R_TAGS + name;
    return ref.length() > Constants.R_TAGS.length() && Repository.isValidRefName(ref) ? ref : null;
  }

  /**
   * A repository-relative file path: no leading slash, no {@code .} or {@code ..} segment, no empty
   * segment, no control character. The rule {@code RepositoryBrowseResource} reads with, applied to
   * what this endpoint writes.
   */
  static boolean isValidPath(String path) {
    if (path == null || path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
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
