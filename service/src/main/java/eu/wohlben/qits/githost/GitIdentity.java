package eu.wohlben.qits.githost;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Who a commit this host manufactures is attributed to.
 *
 * <p>Until the git primitives landed this service authored <b>nothing</b>: every object in every
 * repository arrived through receive-pack, already signed by whoever pushed it, and a git identity
 * was a concept qits-githost did not need. The primitives change that — a merge commit, a version-
 * bump commit and an annotated tag are objects this process builds — so there has to be an answer
 * to "who made this", and it has to be one answer rather than a literal at each call site.
 *
 * <p><b>The keys are the platform's, not this service's</b>: {@code qits.git.author-name} and
 * {@code qits.git.author-email}, the same pair qits-workspaces' {@code GitIdentity} reads for the
 * commits <i>it</i> manufactures, with the same {@code qits} / {@code qits@local} defaults. Two
 * services making commits on the platform's behalf under two different names would be a worse
 * outcome than either name, so the key is shared deliberately and an operator sets it once.
 *
 * <p>A caller may name its own author instead ({@code {"author": {"name": …, "email": …}}} on the
 * primitive requests) — qits-projects releasing on a person's behalf is the case that will want it.
 * Author and committer are then the same person: this host has no second identity to offer and
 * inventing a split would be a claim nobody made.
 */
@ApplicationScoped
public class GitIdentity {

  @ConfigProperty(name = "qits.git.author-name", defaultValue = "qits")
  String name;

  @ConfigProperty(name = "qits.git.author-email", defaultValue = "qits@local")
  String email;

  /** The configured identity, stamped at the current instant. */
  public PersonIdent person() {
    return new PersonIdent(name, email);
  }

  /**
   * The caller's identity when it named one, the configured identity otherwise. Both halves must be
   * present and non-blank to count — a name with no address is not an identity git can record.
   */
  public PersonIdent person(String requestedName, String requestedEmail) {
    if (isBlank(requestedName) || isBlank(requestedEmail)) {
      return person();
    }
    return new PersonIdent(requestedName.trim(), requestedEmail.trim());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
