package eu.wohlben.qits.githost.loc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Counts a commit's lines of code per language, split test from main — one recursive walk over the
 * commit's tree, reading blobs straight out of the object database.
 *
 * <p>There is nothing else to read: a DFS-backed repository has no worktree and can grow none, so
 * "check it out and run a counter over the files" is not a slower alternative here, it is not an
 * alternative at all. The walk is the same shape the browse tree endpoint uses.
 *
 * <p><b>What counts.</b> A regular or executable blob whose path {@link Language} names, at most
 * {@link #MAX_COUNTED_BLOB_BYTES}, and not binary by {@link RawText#isBinary}. Gitlinks and
 * symlinks are skipped (no blob to count, a target string respectively), and an unnamed path is
 * skipped <em>before</em> its blob is read — which is most of the walk, and most of the saving.
 * Lines are {@link RawText#size()}, the diff machinery's own idea of a line.
 *
 * <p>The result is a pure function of the commit, sorted largest total first (ties by name) so the
 * stored copy and the wire answer are deterministic.
 */
public final class RepositoryLocScanner {

  /**
   * Past this a blob is skipped: the browse plane's {@code MAX_CONTENT_BYTES} degrade point, reused
   * as a cap here because a text file that large is generated content that would swamp the numbers.
   */
  static final long MAX_COUNTED_BLOB_BYTES = 2 * 1024 * 1024;

  private RepositoryLocScanner() {}

  public static List<LanguageLoc> scan(Repository repo, RevCommit commit) throws IOException {
    Map<String, long[]> byLanguage = new HashMap<>(); // [mainLines, testLines]
    try (TreeWalk walker = new TreeWalk(repo)) {
      walker.addTree(commit.getTree());
      walker.setRecursive(true);
      while (walker.next()) {
        FileMode mode = walker.getFileMode(0);
        if (FileMode.GITLINK.equals(mode) || FileMode.SYMLINK.equals(mode)) {
          continue;
        }
        String path = walker.getPathString();
        Optional<String> language = Language.of(path);
        if (language.isEmpty()) {
          continue;
        }
        ObjectLoader loader = repo.open(walker.getObjectId(0), Constants.OBJ_BLOB);
        if (loader.getSize() > MAX_COUNTED_BLOB_BYTES) {
          continue;
        }
        byte[] bytes = loader.getBytes((int) MAX_COUNTED_BLOB_BYTES);
        if (RawText.isBinary(bytes, bytes.length, true)) {
          continue;
        }
        long lines = new RawText(bytes).size();
        long[] counts = byLanguage.computeIfAbsent(language.get(), key -> new long[2]);
        counts[TestPath.isTest(path) ? 1 : 0] += lines;
      }
    }
    List<LanguageLoc> result = new ArrayList<>(byLanguage.size());
    for (Map.Entry<String, long[]> entry : byLanguage.entrySet()) {
      result.add(new LanguageLoc(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
    }
    result.sort(
        Comparator.comparingLong((LanguageLoc l) -> l.mainLines() + l.testLines())
            .reversed()
            .thenComparing(LanguageLoc::language));
    return result;
  }
}
