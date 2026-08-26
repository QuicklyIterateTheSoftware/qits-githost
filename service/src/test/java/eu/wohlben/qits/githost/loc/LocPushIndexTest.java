package eu.wohlben.qits.githost.loc;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.githost.GitRepositoryProvider;
import eu.wohlben.qits.githost.persistence.RepositoryLocStore;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The push path of the lines-of-code memo: a push alone — no browse request — must leave the tip's
 * summary stored, because {@code LocAnnouncer} queued it and {@code LocIndexer} scanned it on its
 * own thread. Seeded through the served git endpoint like the rest of the suite; the git helper is
 * re-spelled here for the same package-visibility reason {@code RepositoryBrowseResourceTest}
 * documents.
 */
@QuarkusTest
public class LocPushIndexTest {

  @Inject GitRepositoryProvider repositories;
  @Inject RepositoryLocStore store;

  @TestHTTPResource("/git")
  URL gitBase;

  @Test
  public void aPushLeavesTheTipsSummaryStoredWithoutAnyBrowseRequest() throws Exception {
    String repoId = UUID.randomUUID().toString();
    repositories.create(repoId, "main");

    Path work = Files.createTempDirectory("qits-loc-push-seed");
    git(work, "init", "-q", "-b", "main", ".");
    Files.createDirectories(work.resolve("src/main/java"));
    Files.writeString(work.resolve("src/main/java/App.java"), "line\n".repeat(3));
    git(work, "add", ".");
    git(work, "-c", "user.email=qits@local", "-c", "user.name=qits", "commit", "-q", "-m", "seed");
    String tip = git(work, "rev-parse", "HEAD").trim();
    git(work, "push", "-q", gitBase + "/" + repoId, "main");

    String payload = awaitStored(repoId, tip);
    assertThat(payload, containsString("\"language\":\"Java\""));
    assertThat(payload, containsString("\"mainLines\":3"));
  }

  /** The indexer runs on its own thread; a few seconds is plenty for one three-line scan. */
  private String awaitStored(String repoId, String commitSha) throws InterruptedException {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < deadline) {
      var stored = store.find(repoId, commitSha);
      if (stored.isPresent()) {
        return stored.get();
      }
      Thread.sleep(50);
    }
    fail("the push did not leave a loc summary for " + repoId + "@" + commitSha);
    return null; // unreachable
  }

  private static String git(Path cwd, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(cwd.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new RuntimeException("git " + String.join(" ", args) + " failed:\n" + out);
    }
    return out;
  }
}
