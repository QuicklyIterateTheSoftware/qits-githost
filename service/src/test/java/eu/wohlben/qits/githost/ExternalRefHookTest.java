package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

class ExternalRefHookTest {

  @Test
  void allowsAnExternalBranch() {
    ReceiveCommand command = create("refs/heads/external/alice/topic");

    assertFalse(
        ExternalRefHook.rejectOutsideExternalBranches(
            List.of(command), ExternalRefHook.EXTERNAL_BRANCH_PATTERN));
    assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, command.getResult());
  }

  @Test
  void refusesMainTagNotesAndDeletingMain() {
    for (ReceiveCommand command :
        List.of(
            create("refs/heads/main"),
            create("refs/tags/v1"),
            create("refs/notes/review"),
            delete("refs/heads/main"))) {
      assertTrue(
          ExternalRefHook.rejectOutsideExternalBranches(
              List.of(command), ExternalRefHook.EXTERNAL_BRANCH_PATTERN));
      assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, command.getResult());
      assertEquals(ExternalRefHook.REFUSAL, command.getMessage());
    }
  }

  @Test
  void refusesEveryCommandInAMixedPush() {
    ReceiveCommand allowed = create("refs/heads/external/alice/topic");
    ReceiveCommand forbidden = create("refs/heads/main");

    assertTrue(
        ExternalRefHook.rejectOutsideExternalBranches(
            List.of(allowed, forbidden), ExternalRefHook.EXTERNAL_BRANCH_PATTERN));

    assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, allowed.getResult());
    assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, forbidden.getResult());
  }

  @Test
  void externalBranchDeletionRemainsInsideTheGrantedNamespace() {
    ReceiveCommand command = delete("refs/heads/external/alice/topic");

    assertFalse(
        ExternalRefHook.rejectOutsideExternalBranches(
            List.of(command), ExternalRefHook.EXTERNAL_BRANCH_PATTERN));
    assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, command.getResult());
  }

  @Test
  void onlyTheStandaloneExternalRoleIsRestricted() {
    assertTrue(access("qits:git:external").externalOnly());
    assertTrue(
        access("qits:git:external")
            .externalRefPattern()
            .equals(ExternalRefHook.EXTERNAL_BRANCH_PATTERN));
    assertFalse(access("qits:admin").externalOnly());
    assertFalse(access("qits:system").externalOnly());
    assertFalse(access("qits:git:external", "qits:admin").externalOnly());
    assertFalse(access("qits:git:external", "qits:system").externalOnly());
    assertFalse(access("some:other:role").externalOnly());
    assertFalse(GitHostRoutes.PushAccess.from(null).externalOnly());
  }

  @Test
  void externalRoleWithoutTheApprovedClaimCannotPushAnything() {
    ReceiveCommand command = create("refs/heads/external/alice/topic");

    assertTrue(ExternalRefHook.rejectOutsideExternalBranches(List.of(command), null));
    assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, command.getResult());
    assertEquals(ExternalRefHook.INVALID_CREDENTIAL_REFUSAL, command.getMessage());
  }

  private static ReceiveCommand create(String ref) {
    return new ReceiveCommand(ObjectId.zeroId(), ObjectId.fromString("1111111111111111111111111111111111111111"), ref);
  }

  private static ReceiveCommand delete(String ref) {
    return new ReceiveCommand(ObjectId.fromString("1111111111111111111111111111111111111111"), ObjectId.zeroId(), ref);
  }

  private static GitHostRoutes.PushAccess access(String... roles) {
    QuarkusSecurityIdentity.Builder identity =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(
                new FakeJwt(
                    "workstation",
                    Map.of("git_ref_pattern", ExternalRefHook.EXTERNAL_BRANCH_PATTERN)));
    for (String role : roles) {
      identity.addRole(role);
    }
    return GitHostRoutes.PushAccess.from(identity.build());
  }

  private record FakeJwt(String name, Map<String, Object> claims) implements JsonWebToken {
    @Override
    public String getName() {
      return name;
    }

    @Override
    public Set<String> getClaimNames() {
      return claims.keySet();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getClaim(String claimName) {
      return (T) claims.get(claimName);
    }
  }
}
