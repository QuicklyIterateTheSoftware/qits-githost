package eu.wohlben.qits.githost;

import eu.wohlben.qits.githost.persistence.RepositoryProtectionStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The default branch's seatbelt: it builds a {@link PreReceiveHook} that refuses a direct update or
 * delete of whatever {@code HEAD} points at, so that releasing becomes something the platform does
 * (through qits-workspaces' integrate endpoint) rather than something a person remembers to do.
 *
 * <p><b>This is not a lock and must not pretend to be one.</b> What it guards against is reflex:
 * the muscle memory of {@code git push …/artifacts/git/<repo> main} that every doc in this tree
 * taught first. There used to be a second door beside it — anything with the {@code
 * qits-repositories} volume mounted moved a ref by writing a file — and that volume is gone, so
 * receive-pack, and therefore this hook, is now on the only path in.
 *
 * <p>Why a Java hook and not a {@code hooks/pre-receive} script: this host runs no git. {@link
 * GitHostRoutes} drives JGit's {@link ReceivePack} in-process from raw Vert.x routes — no CGI, no
 * {@code git http-backend}, no subprocess — so a script in the bare's {@code hooks/} would never
 * execute and {@code GIT_PUSH_OPTION_*} is not a concept on this path. JGit hands the options to
 * this hook directly, as {@link ReceivePack#getPushOptions()}.
 *
 * <h2>What is protected</h2>
 *
 * <p>The protected ref is {@link Repository#getFullBranch()} — the repository's own {@code HEAD}.
 * That is {@code refs/heads/main} for every repository here, it is per-repo with nothing to keep in
 * step, it needs no cross-service read of qits-projects' {@code Repository.mainBranch}, and it is
 * semantically the right answer ("the repo's default branch") rather than a hardcoded string. It is
 * also the one thing that survived the move to DFS untouched: under reftable {@code HEAD} is a
 * symbolic ref in the ref database like any other. Every other ref is untouched: this is a guard on
 * one branch, not an authorization system.
 *
 * <p><b>Creates are allowed.</b> An empty repository has no default branch to protect and blocking
 * its seeding push buys no safety — which is also what keeps {@code qits-local-up.sh}'s first-run
 * push working with no option at all. Updates and deletes of the protected ref are guarded.
 *
 * <h2>The two bypasses, and a third option that is not one</h2>
 *
 * <ul>
 *   <li>{@code -o qits.release} — "this is an integrate-produced release". <b>Fast-forward only</b>,
 *       which is what bounds it: the integrate flow's push is a compare-and-swap, and granting it
 *       force would defeat that. Not a secret; it is the sanctioned domain door.
 *   <li>{@code -o qits.token=<value>} — "push anyway", allowing any update including a
 *       non-fast-forward and a delete, <b>iff</b> the value equals {@code
 *       qits.repositories.git.push-token}. That property has <b>no default: unset means no token
 *       matches</b>, and a configured-empty value likewise matches nothing (never "empty allows
 *       empty"). With protection on and no token configured, direct pushes to the default branch
 *       are simply impossible, and a deployment that wants the dev-loop escape configures one.
 *   <li>{@code -o qits.no-ci} — <b>not</b> a bypass of this hook, and this class never reads it: it
 *       skips the CI post-receive POST for the push, and only that one — qits-projects still gets
 *       its event and still backs the repository up ({@code GitHostRoutes.service}'s
 *       post-receive lambda, {@code PostReceiveNotifier}). It grants no write this pusher did not
 *       already have — a push that could carry it could equally push nothing at all — so it needs no
 *       gate of its own. Named here because it rides the same push-options channel as the two
 *       bypasses above and is easy to look for in the wrong class.
 * </ul>
 *
 * <p>Push options rather than a header, because a header cannot serve all three doors this host is
 * reachable through: qits-gateway strips the whole {@code X-Qits-} prefix unconditionally, so an
 * {@code X-Qits-Force-Push} would be present on qits-net and absent through the front door — a
 * bypass whose behaviour depends on which url you used. Options ride inside the pack protocol and
 * are identical through every door.
 *
 * <p>Both bypasses are logged at INFO when they are accepted, the token value never echoed, so "how
 * much direct-to-main pushing is still happening" stays a question with an answer.
 *
 * <h2>Turning it on</h2>
 *
 * <p>{@code qits.repositories.git.protect-default-branch} is the platform-wide switch and ships
 * <b>false</b>: a host that refuses pushes is the host that serves its own redeploy, so this shipped
 * inert and is flipped once every legitimate pusher has been taught the options. The per-repo
 * override is a row, {@link RepositoryProtectionStore}.
 *
 * <p>It was {@code [qits] protectDefaultBranch} in the bare's own config, read straight off the
 * repository JGit had already opened. That reading has no future: a DFS-backed repository has no
 * config file at all — {@code DfsRepository.getConfig()} is an in-memory {@code DfsConfig} whose
 * load and save are no-ops — so the call would have answered the platform default for every
 * repository with no symptom anywhere. The row replaces it for <b>both</b> backends rather than only
 * the new one: one question with two answer sources is a question that eventually gets two answers.
 *
 * <p>The hook is therefore <b>bound to a repo id</b> before it is installed ({@link
 * #forRepository}). {@code GitHostRoutes} already knows the id it resolved; deriving it from the
 * repository's directory would work on one backend and return nothing on the other.
 *
 * <p>JGit's own {@code validateCommands()} runs <i>before</i> this hook and is what makes
 * fast-forward detection reliable here: by the time a command reaches {@link #onPreReceive} its type
 * is already refined to {@code UPDATE} or {@code UPDATE_NONFASTFORWARD}.
 */
@ApplicationScoped
public class ProtectedRefHook {

  private static final Logger LOG = Logger.getLogger(ProtectedRefHook.class);

  /** The sanctioned domain door: an integrate-produced release. Fast-forward only. */
  static final String RELEASE_OPTION = "qits.release";

  /** {@code -o qits.token=<value>} — everything after this prefix is the presented value. */
  static final String TOKEN_OPTION_PREFIX = "qits.token=";

  /**
   * Named in every refusal. A refusal a human cannot act on is a worse bug than the accidental push
   * it prevented, so the message says where releases go and what the alternative requires.
   */
  static final String INTEGRATE_ENDPOINT = "POST /workspaces/api/workspaces/{id}/integrate";

  /**
   * The platform-wide switch, <b>false</b> in the shipped defaults. See the class javadoc: the
   * rollout is ordered rather than a flag day, and default-off is what makes it impossible for a
   * protection bug to reject the push that fixes it.
   */
  @ConfigProperty(name = "qits.repositories.git.protect-default-branch", defaultValue = "false")
  boolean protectByDefault;

  /**
   * The push token, deliberately with <b>no default</b>. Unset matches nothing and empty matches
   * nothing — a deployment that wants the escape hatch configures a value and the pusher presents
   * it; a deployment that configures none has no escape hatch at all.
   */
  @ConfigProperty(name = "qits.repositories.git.push-token")
  Optional<String> pushToken;

  @Inject RepositoryProtectionStore protections;

  /**
   * The hook to install on a {@code ReceivePack}, carrying the repo id the route resolved.
   *
   * <p>A {@code PreReceiveHook} is handed only the {@code ReceivePack}, and the id cannot be
   * recovered from the repository on every backend — a bare has a directory whose parent is the id,
   * a DFS repository has no directory at all. So the id is bound here, where it is still known.
   */
  public PreReceiveHook forRepository(String repoId) {
    return (rp, commands) -> onPreReceive(repoId, rp, commands);
  }

  void onPreReceive(String repoId, ReceivePack rp, Collection<ReceiveCommand> commands) {
    Repository repo = rp.getRepository();
    if (!isProtectionEnabled(repoId)) {
      return;
    }
    String protectedRef = protectedRef(repo);
    if (protectedRef == null) {
      return;
    }

    List<String> options = rp.getPushOptions() == null ? List.of() : rp.getPushOptions();
    boolean release = options.contains(RELEASE_OPTION);
    boolean tokenPresented = options.stream().anyMatch(o -> o.startsWith(TOKEN_OPTION_PREFIX));
    boolean tokenAccepted = tokenPresented && tokenMatches(options);

    for (ReceiveCommand cmd : commands) {
      if (!protectedRef.equals(cmd.getRefName()) || cmd.getType() == ReceiveCommand.Type.CREATE) {
        // Another ref, or the branch being created for the first time. Neither is guarded.
        continue;
      }
      if (tokenAccepted) {
        LOG.infof(
            "push token accepted for protected ref %s in %s (%s %s -> %s)",
            protectedRef, repoId, cmd.getType(), cmd.getOldId().name(), cmd.getNewId().name());
        continue;
      }
      if (release && cmd.getType() == ReceiveCommand.Type.UPDATE) {
        // UPDATE (not UPDATE_NONFASTFORWARD) is JGit's post-validateCommands verdict that this is a
        // fast-forward — the integrate flow's compare-and-swap, arriving as designed.
        LOG.infof(
            "push option %s accepted for protected ref %s in %s (fast-forward %s -> %s)",
            RELEASE_OPTION,
            protectedRef,
            repoId,
            cmd.getOldId().name(),
            cmd.getNewId().name());
        continue;
      }
      String reason = refusal(protectedRef, cmd, release, tokenPresented);
      LOG.infof("refused %s of protected ref %s in %s: %s", cmd.getType(), protectedRef, repoId,
          reason);
      cmd.setResult(Result.REJECTED_OTHER_REASON, reason);
    }
  }

  /**
   * The platform-wide setting, overridden per repository by a protection row when there is one.
   *
   * <p>The store answers "no override" for a row that is absent <b>and</b> for a read it could not
   * make, which is deliberate: an unreadable answer must not decide the question in either
   * direction, so both fall through to the platform setting.
   */
  private boolean isProtectionEnabled(String repoId) {
    return protections.protectionOverride(repoId).orElse(protectByDefault);
  }

  /**
   * The bare's {@code HEAD}, as a full ref name. Anything that is not a branch — a detached HEAD, an
   * unreadable one — protects nothing: there is no default branch to guard, and inventing one would
   * be a guess.
   */
  private String protectedRef(Repository repo) {
    try {
      String full = repo.getFullBranch();
      return full != null && full.startsWith("refs/heads/") ? full : null;
    } catch (Exception e) {
      LOG.debugf(e, "could not read HEAD; nothing is protected");
      return null;
    }
  }

  /**
   * Unset matches nothing; empty matches nothing. Both cases mean "this deployment configured no
   * escape hatch", and an empty presented value must never satisfy an empty configured one.
   */
  private boolean tokenMatches(List<String> options) {
    String configured = pushToken.orElse("");
    if (configured.isEmpty()) {
      return false;
    }
    return options.stream()
        .filter(o -> o.startsWith(TOKEN_OPTION_PREFIX))
        .map(o -> o.substring(TOKEN_OPTION_PREFIX.length()))
        .anyMatch(configured::equals);
  }

  /**
   * The one line git prints back to the pusher. It names where releases go and what the alternative
   * requires, and it <b>never echoes a configured value</b> — only whether this host has one at all,
   * which is the part the pusher cannot otherwise know and can act on.
   */
  private String refusal(
      String ref, ReceiveCommand cmd, boolean release, boolean tokenPresented) {
    boolean delete = cmd.getType() == ReceiveCommand.Type.DELETE;
    String prefix = "protected ref " + ref + ": ";
    if (tokenPresented) {
      return prefix
          + (hasConfiguredToken()
              ? "the -o " + TOKEN_OPTION_PREFIX + "… value does not match this host's push token"
              : "this host has no push token configured, so -o "
                  + TOKEN_OPTION_PREFIX
                  + "… cannot authorize a push")
          + "; release through "
          + INTEGRATE_ENDPOINT;
    }
    if (release) {
      return prefix
          + "-o "
          + RELEASE_OPTION
          + (delete
              ? " never deletes it"
              : " is fast-forward only and this push is not a fast-forward — re-merge onto the"
                  + " current tip")
          + "; a matching -o "
          + TOKEN_OPTION_PREFIX
          + "<value> is what overrides that";
    }
    return prefix
        + (delete ? "deleting" : "updating")
        + " it directly is refused — release through "
        + INTEGRATE_ENDPOINT
        + ", or push with a matching -o "
        + TOKEN_OPTION_PREFIX
        + "<value>";
  }

  private boolean hasConfiguredToken() {
    return !pushToken.orElse("").isEmpty();
  }
}
