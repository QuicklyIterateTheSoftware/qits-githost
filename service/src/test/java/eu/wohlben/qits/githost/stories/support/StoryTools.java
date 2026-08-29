package eu.wohlben.qits.githost.stories.support;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the command-line tools the git stories drive actually are — resolved once per JVM, and
 * answerable as a plain {@code boolean} so a story class can gate itself with {@code @EnabledIf}.
 *
 * <p><b>The resolved value is passed as an ARGUMENT, never written into a template.</b> A story
 * spells {@code commands.run("{} clone {} {}", StoryTools.git(), url, "work")} rather than {@code
 * run("git clone …")}, for the same reason a URL is never spelled into one: the display line gets
 * the real program while the fingerprint keeps {@code {}}, so the story's definition hash is the
 * same on a workstation, in CI and in a container that resolves the tool somewhere else.
 *
 * <p><b>Missing is a SKIP, and the skip must happen before the story starts.</b> The {@code
 * *Present()} predicates below exist for {@code @EnabledIf} at class level; a story body must never
 * call {@code assumeTrue}, because the userflows extension has already opened a report by then and
 * an aborted story emits a red one. A skipped story emits nothing at all, which is the honest
 * answer for "this machine has no git".
 *
 * <p>Two tools, and the split is the two planes this service serves. <b>git</b> is the smart-HTTP
 * wire — what a developer, a workspace container and every CI checkout speak. <b>curl</b> is the
 * plain-HTTP reader: the content routes ({@code …/blob/…}, {@code …/tree/…}) and the browser
 * plane's JSON API are ordinary GETs, and a story drives them with a real client over a real socket
 * rather than with RestAssured — the whole point of the packaged-process stories is that nothing in
 * this JVM is on the path the traffic takes.
 */
public final class StoryTools {

  /** An explicit override for the git binary; unset on every machine seen so far. */
  public static final String GIT_PROPERTY = "qits.userflows.git";

  /** An explicit override for curl. */
  public static final String CURL_PROPERTY = "qits.userflows.curl";

  /**
   * One resolution per tool per JVM. {@code @EnabledIf} is evaluated once per class and a story may
   * ask again, so the {@code PATH} walk should happen once; {@link Optional} rather than {@code
   * null} because {@link ConcurrentHashMap} admits no null value.
   */
  private static final Map<String, Optional<String>> RESOLVED = new ConcurrentHashMap<>();

  private StoryTools() {}

  /** The git CLI: the override property, else {@code PATH}. */
  public static String git() {
    return require("git");
  }

  public static boolean gitPresent() {
    return resolve("git", GIT_PROPERTY).isPresent();
  }

  /** curl: the override property, else {@code PATH}. */
  public static String curl() {
    return require("curl");
  }

  public static boolean curlPresent() {
    return resolve("curl", CURL_PROPERTY).isPresent();
  }

  /**
   * Both tools — the shape {@code @EnabledIf} takes for a story that clones <i>and</i> reads a file
   * back over plain HTTP.
   */
  public static boolean gitAndCurlPresent() {
    return gitPresent() && curlPresent();
  }

  private static String require(String tool) {
    return resolve(tool, property(tool))
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no "
                        + tool
                        + " on this machine — a story needing it must be gated with @EnabledIf on"
                        + " StoryTools#"
                        + tool
                        + "Present"));
  }

  private static String property(String tool) {
    return "git".equals(tool) ? GIT_PROPERTY : CURL_PROPERTY;
  }

  private static Optional<String> resolve(String tool, String property) {
    return RESOLVED.computeIfAbsent(tool, key -> declared(property).or(() -> onPath(key)));
  }

  /**
   * A system property naming an executable. A property that is unset, blank or names something that
   * is not executable is <b>not</b> an error: it has to fall through to {@code PATH} rather than
   * fail a machine that has the tool there anyway.
   */
  private static Optional<String> declared(String property) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    Path candidate = Path.of(value.strip());
    return Files.isExecutable(candidate) && !Files.isDirectory(candidate)
        ? Optional.of(candidate.toAbsolutePath().toString())
        : Optional.empty();
  }

  /**
   * The bare tool name, if some {@code PATH} entry holds it. The <b>name</b> rather than the
   * absolute path on purpose: {@code ProcessBuilder} resolves it identically, and a transcript
   * reading {@code git clone …} is the line a reader would retype.
   */
  private static Optional<String> onPath(String tool) {
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return Optional.empty();
    }
    for (String entry : path.split(File.pathSeparator)) {
      if (entry.isBlank()) {
        continue;
      }
      Path candidate = Path.of(entry).resolve(tool);
      if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
        return Optional.of(tool);
      }
    }
    return Optional.empty();
  }
}
