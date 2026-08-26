package eu.wohlben.qits.githost;

import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.lib.Repository;

/**
 * Port: turns a repo id into an open {@link Repository}. The <b>one</b> seam between the git host's
 * wire protocol and where its bytes actually live.
 *
 * <p>Everything above this is storage-agnostic: {@code UploadPack}/{@code ReceivePack} take a {@code
 * Repository}, so {@code GitHostRoutes.infoRefs} and {@code GitHostRoutes.service} never ask where
 * the bytes came from. Only opening it knows, which is why this interface has as few methods as it
 * does.
 *
 * <p>One implementation ships: {@code DfsGitRepositoryProvider}, packs and refs as blobs. The port
 * stays a port because it is what keeps that fact in one class.
 */
public interface GitRepositoryProvider {

  /**
   * Opens the repository, or returns {@code null} if the store holds no such repository — which the
   * routes answer as a 404, the same answer an id that is not a valid slug gets. The caller closes
   * what it gets back.
   *
   * <p><b>Null is an answer, not a failure.</b> A store that cannot say whether the repository is
   * there throws — unchecked, so this signature stays as narrow as it is — and the routes make a 500
   * of it. A store that cannot answer must never claim absence: this used to read a failed lookup as
   * "no such repository", and a postgres cutover that severed the connection pool then made a live
   * repository answer 404 to a push.
   */
  Repository open(String repoId);

  /**
   * Creates an empty repository whose {@code HEAD} names {@code defaultBranch}, which need not exist
   * yet — the shape a freshly provisioned origin has, and what makes the first push a create.
   *
   * <p>{@code PUT /git/:repoId} ({@link GitHostRoutes}) is the route that calls this — the
   * git-host lifecycle API ({@code projects-volume-decoupling-plan.md} §2) that replaced "creation
   * reaches this host as {@code git clone --mirror} or {@code git init --bare} on the shared
   * volume". It is also the only door a repository can be provisioned through — no directory exists
   * to make one beside it.
   *
   * @throws IOException if the repository already exists or cannot be created
   */
  void create(String repoId, String defaultBranch) throws IOException;

  /**
   * Deletes the repository — every row this host keys by {@code repoId}, in one transaction.
   *
   * <p>{@code DELETE /git/:repoId} ({@link GitHostRoutes}) is the route that calls this, and
   * qits-projects calls that route when a repository row is deleted there. The verb exists because
   * the alternative was a bare left behind for every repository the platform ever removed, with
   * nothing able to reach it: an id nobody holds is an address nobody can clean up.
   *
   * <p><b>Rows go, bytes stay.</b> The pack blobs live in the shared content-addressed store, which
   * counts no references and has no delete on this path, so they are orphaned rather than reclaimed
   * — the same thing a repack leaves behind today, and the same census sweep will be what collects
   * both.
   *
   * @return {@code true} if the repository existed and is now gone, {@code false} if the store held
   *     no such repository — which the route answers as a 404
   */
  boolean delete(String repoId);

  /**
   * Every repository this host currently holds, by id — the enumeration {@code GET
   * /git} answers, and what qits-ci's trigger engine reads to know which repositories an
   * event-triggered pipeline could fire for.
   *
   * <p><b>Order is not part of this contract and the route does not trust it.</b> The wire answer is
   * sorted lexicographically and filtered to ids that are valid repo-id slugs, both in {@link
   * GitHostRoutes}: sorting is a property of the response, and the slug rule is a property of the
   * url, so neither belongs in a backend that could get it subtly wrong.
   *
   * <p>Unlike {@link #open}, this <b>throws rather than reads empty</b>. A catalog that cannot be
   * read is not a host with no repositories, and answering an empty list would leave the trigger
   * engine believing it had nothing to trigger — a failure with no symptom anywhere. An empty list
   * means exactly one thing: this host serves no repository yet.
   *
   * @throws IOException if the store cannot be enumerated
   */
  List<String> repositoryIds() throws IOException;
}
