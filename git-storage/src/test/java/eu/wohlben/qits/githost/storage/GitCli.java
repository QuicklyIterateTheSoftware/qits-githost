package eu.wohlben.qits.githost.storage;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The real git CLI, driven as a workspace container drives it.
 *
 * <p>JGit's own porcelain would prove much less. What is under test is whether the client every
 * developer and every pipeline runs can clone from and push to a repository with no directory behind
 * it — including the parts of the protocol only a real client negotiates, {@code --atomic} among
 * them.
 *
 * <p>Every invocation runs with the user's global and system git config <b>disabled</b>. That is not
 * tidiness: a developer's {@code http.proxy}, {@code url.*.insteadOf} or credential helper would
 * otherwise reach into a suite whose whole point is that it needs no network, and it would fail on
 * one machine and pass on the next.
 */
final class GitCli {

  private GitCli() {}

  private static final Map<String, String> ISOLATED =
      Map.of(
          "GIT_CONFIG_GLOBAL", "/dev/null",
          "GIT_CONFIG_SYSTEM", "/dev/null",
          "GIT_TERMINAL_PROMPT", "0",
          "GIT_ASKPASS", "true");

  /** A local repository with one commit on {@code main}. */
  static Path repositoryWithOneCommit(Path directory) throws Exception {
    git(null, "git", "init", "-q", "-b", "main", directory.toString());
    return commitFile(directory, "README.md", "first\n", "first");
  }

  /** Writes a file and commits it; returns the repository directory for chaining. */
  static Path commitFile(Path repository, String name, String content, String message)
      throws Exception {
    Files.writeString(repository.resolve(name), content);
    git(repository, "git", "add", name);
    committing(repository, "commit", "-q", "-m", message);
    return repository;
  }

  /**
   * Builds an annotated tag object and hands back its sha, leaving <b>no ref behind</b> — which is
   * what turns a tag into something a push actually carries, and is the release flow's own dance.
   */
  static String tagObject(Path repository, String name, String message) throws Exception {
    committing(repository, "tag", "-a", name, "-m", message);
    String sha = revParse(repository, name);
    git(repository, "git", "tag", "-d", name);
    return sha;
  }

  static Path clone(String url, Path target) throws Exception {
    git(null, "git", "clone", "-q", url, target.toString());
    return target;
  }

  static String revParse(Path repository, String ref) throws Exception {
    return git(repository, "git", "rev-parse", ref).trim();
  }

  static String head(Path repository) throws Exception {
    return revParse(repository, "HEAD");
  }

  /** {@code commit}, {@code tag}, {@code blob} — how a pushed tag is proved to be annotated. */
  static String objectType(Path repository, String sha) throws Exception {
    return git(repository, "git", "cat-file", "-t", sha).trim();
  }

  /** Commits as a fixed identity, so no test depends on a developer's git config. */
  static void committing(Path repository, String... arguments) throws Exception {
    List<String> command = new ArrayList<>(List.of("git", "-c", "user.email=qits@local", "-c",
        "user.name=qits"));
    command.addAll(List.of(arguments));
    git(repository, command.toArray(new String[0]));
  }

  /** Runs git, failing the test with the captured output if it exits non-zero. */
  static String git(Path workingDirectory, String... command) throws Exception {
    Result result = run(workingDirectory, command);
    if (result.exit() != 0) {
      throw new IllegalStateException(
          String.join(" ", command) + " failed:\n" + result.output());
    }
    return result.output();
  }

  /**
   * Runs git expecting it to fail, and returns what it printed. A refused push is the subject of
   * several of these tests, so the message the pusher reads is the assertion.
   */
  static String gitExpectingFailure(Path workingDirectory, String... command) throws Exception {
    Result result = run(workingDirectory, command);
    if (result.exit() == 0) {
      fail(String.join(" ", command) + " unexpectedly succeeded:\n" + result.output());
    }
    return result.output();
  }

  private record Result(int exit, String output) {}

  private static Result run(Path workingDirectory, String... command) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command);
    if (workingDirectory != null) {
      builder.directory(workingDirectory.toFile());
    }
    builder.environment().putAll(ISOLATED);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes());
    return new Result(process.waitFor(), output);
  }
}
