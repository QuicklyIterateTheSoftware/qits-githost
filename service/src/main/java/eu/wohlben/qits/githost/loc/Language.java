package eu.wohlben.qits.githost.loc;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Names the language of a tree path, or answers empty for one the map does not know.
 *
 * <p><b>Unknown means omitted, not "Other".</b> An "Other" bucket would be the largest bucket in
 * almost every repository — lockfiles, licences, generated fixtures — and a number that big says
 * nothing. Omission also carries the scan's main cost saving: a path this class cannot name is
 * skipped before its blob is ever read from storage.
 *
 * <p>The map is a closed, hand-kept list of what the platform's repositories actually hold, not an
 * attempt at linguist. Extending it is one line here, and the summaries of already-scanned commits
 * stay as they were — a stored summary is a memo of this map at the time it was computed.
 */
public final class Language {

  /** Whole basenames that name a language without an extension. Checked before the extension. */
  private static final Map<String, String> BY_BASENAME =
      Map.of(
          "dockerfile", "Dockerfile",
          "containerfile", "Dockerfile",
          "makefile", "Makefile");

  private static final Map<String, String> BY_EXTENSION =
      Map.ofEntries(
          Map.entry("java", "Java"),
          Map.entry("kt", "Kotlin"),
          Map.entry("kts", "Kotlin"),
          Map.entry("ts", "TypeScript"),
          Map.entry("tsx", "TypeScript"),
          Map.entry("js", "JavaScript"),
          Map.entry("jsx", "JavaScript"),
          Map.entry("mjs", "JavaScript"),
          Map.entry("cjs", "JavaScript"),
          Map.entry("html", "HTML"),
          Map.entry("css", "CSS"),
          Map.entry("scss", "CSS"),
          Map.entry("sql", "SQL"),
          Map.entry("xml", "XML"),
          Map.entry("xsd", "XML"),
          Map.entry("json", "JSON"),
          Map.entry("yaml", "YAML"),
          Map.entry("yml", "YAML"),
          Map.entry("md", "Markdown"),
          Map.entry("sh", "Shell"),
          Map.entry("bash", "Shell"),
          Map.entry("properties", "Properties"),
          Map.entry("py", "Python"),
          Map.entry("go", "Go"),
          Map.entry("toml", "TOML"),
          Map.entry("gradle", "Gradle"));

  private Language() {}

  /** The language of one slash-separated tree path, or empty for a path the map does not name. */
  public static Optional<String> of(String path) {
    String basename = basenameOf(path).toLowerCase(Locale.ROOT);
    // "Dockerfile.builder" is still a Dockerfile; the suffix is a variant name, not an extension.
    int firstDot = basename.indexOf('.');
    String bareName = firstDot < 0 ? basename : basename.substring(0, firstDot);
    String byName = BY_BASENAME.get(bareName);
    if (byName != null) {
      return Optional.of(byName);
    }
    int lastDot = basename.lastIndexOf('.');
    if (lastDot <= 0 || lastDot == basename.length() - 1) {
      return Optional.empty(); // no extension, or a dotfile, or a trailing dot
    }
    return Optional.ofNullable(BY_EXTENSION.get(basename.substring(lastDot + 1)));
  }

  private static String basenameOf(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }
}
