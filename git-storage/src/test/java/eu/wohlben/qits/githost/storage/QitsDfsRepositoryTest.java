package eu.wohlben.qits.githost.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The repository itself, without a client: what a fresh one looks like, what {@code create} does,
 * and which of its state survives being reopened.
 *
 * <p>No database, no docker, no Quarkus, no network — the two ports are maps.
 */
class QitsDfsRepositoryTest {

  private static final PersonIdent AUTHOR = new PersonIdent("qits", "qits@local");

  private InMemoryPackBlobStore blobs;
  private InMemoryPackCatalog catalog;

  @BeforeEach
  void setUp() {
    blobs = new InMemoryPackBlobStore();
    catalog = new InMemoryPackCatalog();
  }

  private QitsDfsRepository open(String repositoryId) {
    return new QitsDfsRepositoryBuilder()
        .setRepositoryId(repositoryId)
        .setPackBlobStore(blobs)
        .setPackCatalog(catalog)
        .build();
  }

  @Test
  void aRepositoryWithNothingInTheCatalogIsAnEmptyRepository() throws Exception {
    try (QitsDfsRepository repository = open("empty")) {
      assertFalse(repository.exists());
      assertTrue(repository.getRefDatabase().getRefsByPrefix(RefDatabase.ALL).isEmpty());
      assertEquals(0, catalog.packCount("empty"));
      assertEquals(0, blobs.blobCount());
    }
  }

  @Test
  void createPointsHeadAtTheDefaultBranchAndWritesThatRefIntoTheBlobStore() throws Exception {
    try (QitsDfsRepository repository = open("fresh")) {
      repository.create("main");

      // HEAD is a symref to a branch that does not exist yet — the shape a freshly provisioned
      // origin has, and what makes the first push a create rather than an update.
      assertEquals("refs/heads/main", repository.getFullBranch());
      assertNull(repository.exactRef("refs/heads/main"));

      // And the measurement that decides the whole ref-backend question: writing that one ref
      // needed no table and no new storage primitive. It is a reftable, in the same blob store the
      // packs go to.
      assertEquals(1, catalog.packCount("fresh"));
      assertEquals(1, blobs.blobCount());
    }
  }

  @Test
  void creatingARepositoryThatAlreadyHasRefsIsRefused() throws Exception {
    try (QitsDfsRepository repository = open("twice")) {
      repository.create("main");
    }
    try (QitsDfsRepository reopened = open("twice")) {
      assertThrows(IOException.class, () -> reopened.create("main"));
    }
  }

  @Test
  void theRefDatabaseIsOneCachedInstance() throws Exception {
    try (QitsDfsRepository repository = open("cached")) {
      // A getRefDatabase() that builds a new instance per call compiles, serves an advertisement,
      // and then reads refs wrong, because the ref cache is per instance. It cost the spike an
      // afternoon; this is the guard.
      assertSame(repository.getRefDatabase(), repository.getRefDatabase());
      assertSame(repository.getObjectDatabase(), repository.getObjectDatabase());
    }
  }

  @Test
  void everythingWrittenComesBackFromTheCatalogAlone() throws Exception {
    ObjectId commit;
    try (QitsDfsRepository repository = open("durable")) {
      repository.create("main");
      commit = commitOneFile(repository, "hello\n");
      RefUpdate update = repository.updateRef("refs/heads/main");
      update.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.NEW, update.update());
    }

    // A new repository object over the same two ports is what a restart looks like from here.
    try (QitsDfsRepository reopened = open("durable");
        ObjectReader reader = reopened.newObjectReader()) {
      assertTrue(reopened.exists());
      assertEquals(commit, reopened.exactRef("refs/heads/main").getObjectId());
      assertNotNull(reader.open(commit));
      // A blob, a tree and a commit, counted off the catalog rows rather than off anything held in
      // memory by the instance that wrote them.
      assertEquals(3, reopened.getObjectDatabase().getApproximateObjectCount());
    }
  }

  @Test
  void perRepositoryConfigIsNotStoredAnywhere() throws Exception {
    // Named rather than hidden: DfsRepository answers with a DfsConfig, whose load and save are
    // no-ops. So `[qits] protectDefaultBranch` in a bare's own config — the per-repository override
    // the file-backed host supports — has no home here at all and needs a row somewhere else.
    try (QitsDfsRepository repository = open("config")) {
      repository.create("main");
      StoredConfig config = repository.getConfig();
      config.setBoolean("qits", null, "protectDefaultBranch", true);
      config.save();
    }
    try (QitsDfsRepository reopened = open("config")) {
      assertFalse(reopened.getConfig().getBoolean("qits", "protectDefaultBranch", false));
    }
  }

  @Test
  void twoRepositoriesShareTheStoreAndSeeNothingOfEachOther() throws Exception {
    try (QitsDfsRepository one = open("one")) {
      one.create("main");
      RefUpdate update = one.updateRef("refs/heads/main");
      update.setNewObjectId(commitOneFile(one, "one\n"));
      update.update();
    }
    try (QitsDfsRepository two = open("two")) {
      assertFalse(two.exists());
      assertTrue(two.getRefDatabase().getRefsByPrefix(RefDatabase.ALL).isEmpty());
      assertEquals(0, catalog.packCount("two"));
    }
    // One store, one catalog, two repositories: the repository id is the only thing separating
    // them, which is what lets the platform's whole git host be one blob store.
    assertTrue(catalog.packCount("one") > 0);
  }

  /** A blob, a tree and a commit through JGit's inserter — one pack, written through the ports. */
  private static ObjectId commitOneFile(QitsDfsRepository repository, String content)
      throws IOException {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob =
          inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("file.txt", FileMode.REGULAR_FILE, blob);
      ObjectId treeId = inserter.insert(tree);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      commit.setAuthor(AUTHOR);
      commit.setCommitter(AUTHOR);
      commit.setMessage("one file");
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }
}
