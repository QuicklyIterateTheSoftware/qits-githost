package eu.wohlben.qits.githost.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsGarbageCollector;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Garbage collection over blob storage: it works, and <b>the platform does not run it</b>.
 *
 * <p>Both halves of that sentence are the point of this class, so read the second one before
 * copying anything out of it. {@code DfsGarbageCollector} against a store that cannot delete does
 * not reclaim space — it <em>adds</em> it. The repacked pack is written and the packs it replaced
 * keep their bytes forever, because dropping a pack from the catalog frees nothing. Measured on the
 * platform's largest real repository during the spike: 22 packs and 7.8 MB became 2 packs and 15 MB.
 * One run nearly doubled the footprint.
 *
 * <p>So the recorded posture (⚖2) is that the git host never garbage collects in production, and the
 * growth it accepts instead is roughly three blobs per push — immaterial against a store measured in
 * gigabytes. What this test proves is that the <b>engine</b> is correct if it is ever needed: a
 * repacked repository still serves every object, and pushes still land on it. It is not a licence to
 * schedule it, and {@link #garbageCollectionAddsBytesRatherThanFreeingThem} is the assertion that
 * says so out loud.
 */
class GarbageCollectionTest {

  @TempDir Path work;

  private InMemoryPackBlobStore blobs;
  private InMemoryPackCatalog catalog;
  private GitTestServer server;
  private String repositoryId;
  private Path local;

  @BeforeEach
  void setUp() throws Exception {
    blobs = new InMemoryPackBlobStore();
    catalog = new InMemoryPackCatalog();
    server = GitTestServer.serving(blobs, catalog);
    repositoryId = UUID.randomUUID().toString();
    try (QitsDfsRepository repository = server.open(repositoryId)) {
      repository.create("main");
    }
    local = GitCli.repositoryWithOneCommit(work.resolve("local"));
    GitCli.git(local, "git", "remote", "add", "origin", server.url(repositoryId));
    for (int i = 1; i <= 4; i++) {
      GitCli.commitFile(local, "file" + i + ".txt", "change " + i + "\n", "commit " + i);
      GitCli.git(local, "git", "push", "-q", "origin", "HEAD:refs/heads/main");
    }
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private void collect() throws Exception {
    try (QitsDfsRepository repository = server.open(repositoryId)) {
      new DfsGarbageCollector(repository).pack(NullProgressMonitor.INSTANCE);
      repository.scanForRepoChanges();
    }
  }

  @Test
  void aRepackedRepositoryStillServesEveryObjectAndStillTakesPushes() throws Exception {
    int packsBefore = catalog.packCount(repositoryId);
    String beforeGc = GitCli.head(local);

    collect();

    assertTrue(
        catalog.packCount(repositoryId) < packsBefore,
        "the repack should leave fewer packs than the " + packsBefore + " it started with");

    Path clone = GitCli.clone(server.url(repositoryId), work.resolve("clone"));
    assertEquals(beforeGc, GitCli.head(clone));
    GitCli.git(clone, "git", "fsck", "--strict");

    GitCli.commitFile(local, "after.txt", "after gc\n", "after gc");
    GitCli.git(local, "git", "push", "-q", "origin", "HEAD:refs/heads/main");
    Path second = GitCli.clone(server.url(repositoryId), work.resolve("second"));
    assertEquals(GitCli.head(local), GitCli.head(second));
    GitCli.git(second, "git", "fsck", "--strict");
  }

  @Test
  void garbageCollectionAddsBytesRatherThanFreeingThem() throws Exception {
    int blobsBefore = blobs.blobCount();
    long bytesBefore = blobs.byteCount();

    collect();

    // The number behind the decision not to run this. Every blob the repack replaced is still
    // stored, and the repack's own output is stored beside it, so the repository is strictly bigger
    // than it was — the opposite of what "garbage collection" promises.
    assertTrue(blobs.blobCount() > blobsBefore, "no blob may be dropped: the store has no delete");
    assertTrue(blobs.byteCount() > bytesBefore, "a repack in an append-only store only adds bytes");
  }
}
