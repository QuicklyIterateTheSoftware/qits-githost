package eu.wohlben.qits.githost;

import java.util.Collection;
import org.eclipse.jgit.transport.ReceiveCommand;

/**
 * The receive-pack restriction for an external workstation credential.
 *
 * <p>The identity is classified at the HTTP boundary, while it is still tied to Quarkus's verified
 * request identity. This hook deliberately receives only that immutable decision; it never reads a
 * header or a thread-local while JGit is running on a worker. A workstation may create, update, or
 * delete an {@code external/*} branch, but may not touch any other ref.
 *
 * <p>Refusal is all-or-nothing even when the Git client did not negotiate the protocol's optional
 * {@code atomic} capability. Setting every command to a rejection before JGit applies refs means a
 * push that contains one forbidden ref cannot partly land an otherwise permitted external branch.
 */
final class ExternalRefHook {

  static final String EXTERNAL_BRANCH_PREFIX = "refs/heads/external/";
  static final String EXTERNAL_BRANCH_PATTERN = EXTERNAL_BRANCH_PREFIX + "*";
  static final String REFUSAL = "external workstation credentials may only update refs/heads/external/*";
  static final String INVALID_CREDENTIAL_REFUSAL =
      "external workstation credential has no valid git_ref_pattern claim";

  private ExternalRefHook() {}

  /**
   * Reject every command when any proposed ref is outside the workstation namespace.
   *
   * @return {@code true} when the caller must stop processing hooks and let JGit report the
   *     rejection
   */
  static boolean rejectOutsideExternalBranches(
      Collection<ReceiveCommand> commands, String grantedRefPattern) {
    if (!EXTERNAL_BRANCH_PATTERN.equals(grantedRefPattern)) {
      reject(commands, INVALID_CREDENTIAL_REFUSAL);
      return true;
    }
    boolean forbidden =
        commands.stream().anyMatch(command -> !command.getRefName().startsWith(EXTERNAL_BRANCH_PREFIX));
    if (!forbidden) {
      return false;
    }
    reject(commands, REFUSAL);
    return true;
  }

  private static void reject(Collection<ReceiveCommand> commands, String reason) {
    for (ReceiveCommand command : commands) {
      command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, reason);
    }
  }
}
