package eu.wohlben.qits.githost.loc;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a tree path is test code or main code. Everything is one of the two — a fixture,
 * a helper or a harness under a test directory is test code, and anything the rules do not claim is
 * main. There is no "unknown" because a summary with three columns would need every reader to learn
 * what the third one means.
 *
 * <p>The rules are ordered and the first match wins; they encode the platform's own conventions
 * (Maven's {@code src/test}, Angular's {@code *.spec.ts}, JUnit's {@code *Test.java}) rather than
 * any general theory of test layout.
 */
public final class TestPath {

  /** A directory anywhere on the path that puts everything beneath it in the test column. */
  private static final Set<String> TEST_SEGMENTS =
      Set.of("test", "tests", "spec", "specs", "__tests__");

  private static final Pattern SPEC_SUFFIX = Pattern.compile(".*\\.(spec|test)\\.(ts|tsx|js)$");

  /**
   * Case-sensitive on purpose: lowercased, the failsafe-style {@code *IT.java} rule would claim
   * every {@code edit.java} and {@code commit.java} on the host.
   */
  private static final Pattern JVM_SUFFIX = Pattern.compile(".*(Tests?|IT)\\.(java|kt)$");

  private TestPath() {}

  /** True when {@code path} — slash-separated, repository-relative — is test code. */
  public static boolean isTest(String path) {
    int lastSlash = path.lastIndexOf('/');
    if (lastSlash >= 0) {
      for (String segment : path.substring(0, lastSlash).toLowerCase(Locale.ROOT).split("/")) {
        if (TEST_SEGMENTS.contains(segment)) {
          return true;
        }
      }
    }
    String basename = path.substring(lastSlash + 1);
    return SPEC_SUFFIX.matcher(basename.toLowerCase(Locale.ROOT)).matches()
        || JVM_SUFFIX.matcher(basename).matches();
  }
}
