package eu.wohlben.qits.githost.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real git CLI against a repository that has no directory: clone, push, clone again.
 *
 * <p>This is the suite that decides whether the storage engine is a git host at all. It runs against
 * an in-process transcription of {@code GitHostRoutes} (see {@link GitTestServer}), so what it
 * proves is the pairing of those routes with this storage — and the routes need no change to serve
 * it, because they take a {@code Repository}.
 *
 * <p>Offline, and needs nothing but a {@code git} on the path: no database, no docker, no Quarkus,
 * no network.
 */
class GitProtocolTest {

  @TempDir Path work;

  private InMemoryPackBlobStore blobs;
  private InMemoryPackCatalog catalog;
  private GitTestServer server;
  private String repositoryId;

  @BeforeEach
  void setUp() throws Exception {
    blobs = new InMemoryPackBlobStore();
    catalog = new InMemoryPackCatalog();
    server = GitTestServer.serving(blobs, catalog);
    repositoryId = UUID.randomUUID().toString();
    try (QitsDfsRepository repository = server.open(repositoryId)) {
      repository.create("main");
    }
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private String url() {
    return server.url(repositoryId);
  }

  @Test
  void anEmptyRepositoryClonesAndIsEmpty() throws Exception {
    // A repository that has been created and never pushed to: HEAD names a branch with nothing on
    // it, there is one reftable and no pack, and a clone of it succeeds with no commit in it.
    Path clone = GitCli.clone(url(), work.resolve("clone"));
    assertTrue(GitCli.git(clone, "git", "status", "--short").isBlank());
    GitCli.gitExpectingFailure(clone, "git", "rev-parse", "--verify", "HEAD");
  }

  @Test
  void aPushedCommitComesBackOutOfAFreshClone() throws Exception {
    Path local = GitCli.repositoryWithOneCommit(work.resolve("local"));
    GitCli.git(local, "git", "remote", "add", "origin", url());
    GitCli.git(local, "git", "push", "-q", "origin", "HEAD:refs/heads/main");
    String pushed = GitCli.head(local);

    Path clone = GitCli.clone(url(), work.resolve("clone"));
    assertEquals(pushed, GitCli.head(clone));
    assertEquals("first\n", Files.readString(clone.resolve("README.md")));
    GitCli.git(clone, "git", "fsck", "--strict");

    // HEAD is still a symbolic ref, and it is advertised as one. Under reftable it lives in the ref
    // database like any other ref — which is what makes a clone check out the right branch with no
    // config file anywhere to read it from.
    String advertised = GitCli.git(null, "git", "ls-remote", "--symref", url(), "HEAD");
    assertTrue(advertised.contains("ref: refs/heads/main"), advertised);

    // And the same answer from the storage engine directly, through a repository object that did
    // not exist when the push landed.
    try (QitsDfsRepository reopened = server.open(repositoryId)) {
      assertEquals(pushed, reopened.exactRef("refs/heads/main").getObjectId().name());
    }
  }

  @Test
  void pushAfterPushKeepsTheRepositoryReadableAndTheStoreOnlyGrows() throws Exception {
    Path local = GitCli.repositoryWithOneCommit(work.resolve("local"));
    GitCli.git(local, "git", "remote", "add", "origin", url());

    int blobsBefore = blobs.blobCount();
    for (int i = 1; i <= 4; i++) {
      GitCli.commitFile(local, "file" + i + ".txt", "change " + i + "\n", "commit " + i);
      GitCli.git(local, "git", "push", "-q", "origin", "HEAD:refs/heads/main");
    }

    // One push, one pack — nothing ever repacks, which is the no-GC posture working as designed
    // (⚖2), and the growth rate the plan priced: roughly three blobs per push.
    assertTrue(catalog.packCount(repositoryId) >= 5, "packs: " + catalog.packCount(repositoryId));
    assertTrue(blobs.blobCount() > blobsBefore);

    Path clone = GitCli.clone(url(), work.resolve("clone"));
    assertEquals(GitCli.head(local), GitCli.head(clone));
    assertEquals("change 4\n", Files.readString(clone.resolve("file4.txt")));
    GitCli.git(clone, "git", "fsck", "--strict");
  }

  @Test
  void aFetchAfterAPushBringsOnlyTheNewCommits() throws Exception {
    Path local = GitCli.repositoryWithOneCommit(work.resolve("local"));
    GitCli.git(local, "git", "remote", "add", "origin", url());
    GitCli.git(local, "git", "push", "-q", "origin", "HEAD:refs/heads/main");

    Path clone = GitCli.clone(url(), work.resolve("clone"));
    GitCli.commitFile(local, "second.txt", "second\n", "second");
    GitCli.git(local, "git", "push", "-q", "origin", "HEAD:refs/heads/main");

    GitCli.git(clone, "git", "fetch", "-q", "origin");
    assertEquals(GitCli.head(local), GitCli.revParse(clone, "refs/remotes/origin/main"));
  }

  @Test
  void anAnnotatedTagSurvivesTheRoundTrip() throws Exception {
    Path local = GitCli.repositoryWithOneCommit(work.resolve("local"));
    GitCli.git(local, "git", "remote", "add", "origin", url());
    GitCli.git(local, "git", "push", "-q", "origin", "HEAD:refs/heads/main");

    String tag = GitCli.tagObject(local, "v1", "the first release");
    GitCli.git(local, "git", "push", "-q", "origin", tag + ":refs/tags/v1");

    Path clone = GitCli.clone(url(), work.resolve("clone"));
    assertEquals(tag, GitCli.revParse(clone, "refs/tags/v1"));
    assertEquals("tag", GitCli.objectType(clone, tag));
  }
}
