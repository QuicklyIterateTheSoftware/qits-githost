package eu.wohlben.qits.githost.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The four push behaviours the shipped release flow is built on, proved against blob storage.
 *
 * <p>These are not general git properties — they are the ones {@code ReleaseIntegrator} depends on,
 * and each was measured on the file-backed host before it was depended on. A storage backend that
 * quietly loses any of them breaks releases rather than pushes, which is a much worse way to find
 * out. So they are re-proved here, on this storage, with a real client:
 *
 * <ul>
 *   <li>the receive-pack advertisement offers {@code atomic}, so the capability is negotiated;
 *   <li>an {@code --atomic} push of a branch and a tag together is all-or-nothing — a refused branch
 *       takes the tag down with it;
 *   <li>without {@code --atomic} the same refusal leaves the tag behind, which is the failure the
 *       flag exists to prevent: a tag for a release that never landed;
 *   <li>a non-forced push over an existing tag is refused, which is the version-uniqueness
 *       guarantee.
 * </ul>
 *
 * <p>The first of those is the one that decided the ref backend: JGit's other DFS ref database does
 * not advertise {@code atomic} at all, and a push that passes the flag fails outright against it.
 */
class ReleasePushTest {

  private static final String PROTECTED_MESSAGE = "refs/heads/main is protected here";

  @TempDir Path work;

  private GitTestServer server;
  private String repositoryId;
  private Path local;
  private String seeded;

  @BeforeEach
  void setUp() throws Exception {
    server = GitTestServer.serving(new InMemoryPackBlobStore(), new InMemoryPackCatalog());
    repositoryId = UUID.randomUUID().toString();
    try (QitsDfsRepository repository = server.open(repositoryId)) {
      repository.create("main");
    }
    local = GitCli.repositoryWithOneCommit(work.resolve("local"));
    GitCli.git(local, "git", "remote", "add", "origin", server.url(repositoryId));
    GitCli.git(local, "git", "push", "-q", "origin", "HEAD:refs/heads/main");
    seeded = GitCli.head(local);
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void theReceivePackAdvertisementOffersAtomicAndPushOptions() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            server.url(repositoryId) + "/info/refs?service=git-receive-pack"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("atomic"), response.body());
    assertTrue(response.body().contains("push-options"), response.body());
  }

  @Test
  void anAtomicPushWhoseBranchIsRefusedCreatesNoTag() throws Exception {
    server.refusing("refs/heads/main", PROTECTED_MESSAGE);
    GitCli.commitFile(local, "release.txt", "1.0.0\n", "release 1.0.0");
    String tag = GitCli.tagObject(local, "v1", "release 1.0.0");

    String refusal =
        GitCli.gitExpectingFailure(
            local,
            "git",
            "push",
            "--atomic",
            "-o",
            "qits.release",
            "origin",
            "HEAD:refs/heads/main",
            tag + ":refs/tags/v1");

    assertTrue(refusal.contains(PROTECTED_MESSAGE), refusal);
    try (QitsDfsRepository repository = server.open(repositoryId)) {
      assertEquals(seeded, repository.exactRef("refs/heads/main").getObjectId().name());
      assertNull(repository.exactRef("refs/tags/v1"), "the tag must fall with the branch");
    }
  }

  @Test
  void withoutAtomicTheSameRefusalLeavesTheTagBehind() throws Exception {
    // The contrast that makes --atomic worth passing, and the reason it is asserted rather than
    // assumed: the tag lands for a release whose merge commit was refused.
    server.refusing("refs/heads/main", PROTECTED_MESSAGE);
    GitCli.commitFile(local, "release.txt", "1.0.0\n", "release 1.0.0");
    String tag = GitCli.tagObject(local, "v1", "release 1.0.0");

    String refusal =
        GitCli.gitExpectingFailure(
            local, "git", "push", "origin", "HEAD:refs/heads/main", tag + ":refs/tags/v1");

    assertTrue(refusal.contains(PROTECTED_MESSAGE), refusal);
    try (QitsDfsRepository repository = server.open(repositoryId)) {
      assertEquals(seeded, repository.exactRef("refs/heads/main").getObjectId().name());
      assertEquals(tag, repository.exactRef("refs/tags/v1").getObjectId().name());
    }
  }

  @Test
  void pushingOverAnExistingTagIsRefused() throws Exception {
    String first = GitCli.tagObject(local, "v1", "first release");
    GitCli.git(local, "git", "push", "-q", "origin", first + ":refs/tags/v1");

    GitCli.commitFile(local, "again.txt", "same version\n", "second release");
    String second = GitCli.tagObject(local, "v1", "second release, same version");
    String refusal =
        GitCli.gitExpectingFailure(local, "git", "push", "origin", second + ":refs/tags/v1");

    // The refusal is the client's, off the advertisement — this host allows the move under --force,
    // exactly as JGit's receive.denyNonFastForwards default says it should. So the guarantee holds
    // for a release flow that never forces, and it is the same guarantee the file-backed host gives.
    assertTrue(refusal.contains("already exists"), refusal);
    try (QitsDfsRepository repository = server.open(repositoryId)) {
      assertEquals(first, repository.exactRef("refs/tags/v1").getObjectId().name());
    }
  }
}
