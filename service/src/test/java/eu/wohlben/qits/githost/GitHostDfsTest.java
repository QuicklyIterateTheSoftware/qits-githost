package eu.wohlben.qits.githost;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;

/**
 * {@link GitHostSuite} against the second storage engine: no directory anywhere, packs and refs as
 * blobs in this service's own content-addressed store, listed by rows in the artifacts lineage.
 *
 * <p>One config value separates it from {@link GitHostTest}, which is the claim the seam makes and
 * therefore the claim worth testing: nothing above {@code GitHostRoutes.open} knows which backend it
 * is serving. Every case in the suite runs here — clone, push, a 12 MB pack, the whole protection
 * matrix, the tag measurements and the {@code --atomic} all-or-nothing — because a lever that only
 * carried some of them would leave the interesting half unproved.
 *
 * <p>It is <b>not</b> the full matrix. {@code GitHostPushTokenTest} and {@code
 * GitHostEmptyPushTokenTest} are two further process configurations, and the packaged binary is
 * another; parameterising those over both backends is workstream AX. This is the lever and the proof
 * that the wiring works.
 */
@QuarkusTest
@TestProfile(GitHostDfsTest.DfsStorage.class)
public class GitHostDfsTest extends GitHostSuite {

  public static class DfsStorage implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.repositories.git.storage", "dfs");
    }
  }

  @Override
  String expectedBackend() {
    return "dfs";
  }
}
