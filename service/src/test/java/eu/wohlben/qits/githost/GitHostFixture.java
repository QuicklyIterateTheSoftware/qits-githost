package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.fail;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * The git CLI, driven as a test would drive a workspace container: real repositories provisioned
 * through the host's own storage, real clones over the served HTTP endpoint, real pushes.
 *
 * <p>Static and shared because four {@code @QuarkusTest} classes need it and they share no base
 * class: each carries a profile that would change what the whole suite means, so they stay separate
 * and reach these helpers as statics.
 *
 * <p><b>Nothing here reads a directory</b>, because there is none to read. {@link #emptyOrigin} asks
 * {@link GitRepositoryProvider} to make a repository and every assertion after that goes over the
 * wire. Reading a served bare with {@code rev-parse} was the shortcut this suite was written away
 * from: a {@code DfsRepository} has no directory for the git CLI to open at all.
 *
 * <p>The git CLI rather than JGit's porcelain, for the same reason {@code GitHostTest} always shelled
 * it: what is under test is whether the real client can talk to this host, including the parts of
 * the protocol — push options among them — that only a real client negotiates.
 */
final class GitHostFixture {

  private GitHostFixture() {}

  /**
   * Commits as a fixed identity, so no test depends on a developer's global git config. Everything
   * after {@code repo} is appended to {@code git -c user.email=… -c user.name=…}.
   */
  private static void gitCommitting(Path repo, String... args) throws Exception {
    String[] command = new String[5 + args.length];
    command[0] = "git";
    command[1] = "-c";
    command[2] = "user.email=qits@local";
    command[3] = "-c";
    command[4] = "user.name=qits";
    System.arraycopy(args, 0, command, 5, args.length);
    git(repo, command);
  }

  /**
   * An empty repository whose HEAD names {@code refs/heads/main} with nothing on it — the shape a
   * freshly provisioned origin has, and the one where the protected ref exists only as a symref.
   * Pushing to it is a CREATE, which protection deliberately allows.
   *
   * <p>The only method in this class that knows a repository has to come from somewhere, and it
   * does not know where either: it asks {@link GitRepositoryProvider}. Everything else here goes
   * over the wire.
   *
   * <p>The branch is pinned rather than left to {@code init.defaultBranch}, because the protected
   * ref is the repository's own HEAD and a test that does not know its name proves nothing about it.
   */
  static String emptyOrigin(GitRepositoryProvider provider) throws Exception {
    String repoId = UUID.randomUUID().toString();
    provider.create(repoId, "main");
    return repoId;
  }

  /**
   * An empty repository with one commit pushed onto {@code main} — a repository as the platform's
   * would be after its first push, and the starting point for most of the suite.
   *
   * <p>Seeded <b>through the served endpoint</b> rather than by building a bare beside it, because
   * receive-pack is the only door this storage has.
   */
  static String seedOrigin(GitRepositoryProvider provider, URL gitBase) throws Exception {
    String repoId = emptyOrigin(provider);
    Path seed = Files.createTempDirectory("qits-githost-seed");
    git(null, "git", "init", "-q", "-b", "main", seed.toString());
    Files.writeString(seed.resolve("README.md"), "seed\n");
    git(seed, "git", "add", "README.md");
    gitCommitting(seed, "commit", "-q", "-m", "seed");
    git(seed, "git", "push", "-q", gitBase + "/" + repoId, "main");
    return repoId;
  }

  /**
   * What the served repository says a ref is, or {@code null} if it has none — {@code git ls-remote}
   * rather than a {@code rev-parse} in a directory, because there is no directory. It also asks the
   * question the clients actually ask.
   */
  static String remoteRefSha(URL gitBase, String repoId, String ref) throws Exception {
    return lsRemote(gitBase, repoId, ref, ref);
  }

  /** Fails the test if the ref is absent; returns its sha. */
  static String requireRemoteRefSha(URL gitBase, String repoId, String ref) throws Exception {
    String sha = remoteRefSha(gitBase, repoId, ref);
    if (sha == null) {
      fail("the served repository " + repoId + " has no " + ref);
    }
    return sha;
  }

  /**
   * The sha a tag ref <b>peels to</b>, or {@code null} if it peels to nothing.
   *
   * <p>An advertisement carries {@code <ref>^{}} only for a ref that names a tag OBJECT, so a
   * non-null answer here is how "the ref names an annotated tag rather than the commit" is proved
   * over the wire — the question {@code cat-file -t} answers in a directory that a DFS-backed
   * repository does not have.
   *
   * <p>The pattern is globbed rather than the exact ref, because {@code ls-remote} matches its
   * patterns against ref names and {@code <ref>^{}} is not one: asking for the ref exactly filters
   * the peeled line straight back out.
   */
  static String peeledRemoteRef(URL gitBase, String repoId, String ref) throws Exception {
    return lsRemote(gitBase, repoId, ref + "*", ref + "^{}");
  }

  private static String lsRemote(URL gitBase, String repoId, String pattern, String wanted)
      throws Exception {
    String out = git(null, "git", "ls-remote", gitBase + "/" + repoId, pattern);
    for (String line : out.split("\n")) {
      String[] parts = line.trim().split("\\s+");
      if (parts.length == 2 && parts[1].equals(wanted)) {
        return parts[0];
      }
    }
    return null;
  }

  /** Clones the served repository over HTTP into a fresh temp directory. */
  static Path clone(URL gitBase, String repoId) throws Exception {
    Path clone = Files.createTempDirectory("qits-githost-clone");
    Files.delete(clone); // git clone wants to create the target itself
    git(null, "git", "clone", "-q", gitBase + "/" + repoId, clone.toString());
    return clone;
  }

  /** A local repository with one commit on {@code main}, for pushing at an empty origin. */
  static Path localRepo() throws Exception {
    Path dir = Files.createTempDirectory("qits-githost-local");
    git(null, "git", "init", "-q", "-b", "main", dir.toString());
    return commitFile(dir, "README.md", "local\n", "first");
  }

  /** Writes a file and commits it; returns the repository directory for chaining. */
  static Path commitFile(Path repo, String name, String content, String message) throws Exception {
    Files.writeString(repo.resolve(name), content);
    git(repo, "git", "add", name);
    gitCommitting(repo, "commit", "-q", "-m", message);
    return repo;
  }

  /**
   * Rewrites the tip in place, so the local branch and the origin's share a parent but neither is an
   * ancestor of the other — the shape JGit types as {@code UPDATE_NONFASTFORWARD} once its own
   * {@code validateCommands()} has run, which is what the fast-forward-only rule keys on.
   */
  static void rewriteTip(Path repo, String message) throws Exception {
    gitCommitting(repo, "commit", "-q", "--amend", "-m", message);
  }

  static String head(Path repo) throws Exception {
    return git(repo, "git", "rev-parse", "HEAD").trim();
  }

  static String refSha(Path repo, String ref) throws Exception {
    return git(repo, "git", "rev-parse", ref).trim();
  }

  /**
   * Builds an annotated tag object and hands back its sha, leaving <b>no ref behind</b>.
   *
   * <p>This is the release flow's own dance, not a test convenience: {@code prepareWorktree} runs
   * {@code git worktree add} on the served bare, and a linked worktree shares the common ref store —
   * so {@code git tag -a} there writes {@code refs/tags/…} straight into the bare with no push at
   * all, and the push that follows reports {@code [up to date]} with zero receive commands.
   * Creating the object, capturing its sha and deleting the ref is what turns the tag back into
   * something a push actually carries.
   */
  static String tagObject(Path repo, String name, String message) throws Exception {
    gitCommitting(repo, "tag", "-a", name, "-m", message);
    String sha = refSha(repo, name);
    git(repo, "git", "tag", "-d", name);
    return sha;
  }

  /**
   * Runs git with {@code GIT_CURL_VERBOSE}, so the caller can count the HTTP requests a single push
   * made. That is the only way to tell "one receive-pack carrying two commands" from "two pushes"
   * from outside the server, and the distinction is the whole point of pushing the branch and the
   * tag together.
   */
  static String gitTracingHttp(Path cwd, String... command) throws Exception {
    Result result = run(cwd, Map.of("GIT_CURL_VERBOSE", "1"), command);
    if (result.exit() != 0) {
      throw new RuntimeException("git " + String.join(" ", command) + " failed:\n" + result.out());
    }
    return result.out();
  }

  /** How many times {@code git-receive-pack} was POSTed, out of {@link #gitTracingHttp} output. */
  static long receivePackRequests(String trace) {
    return trace.lines().filter(l -> l.contains("POST") && l.contains("git-receive-pack")).count();
  }

  /** Runs git, failing the test with the captured output if it exits non-zero. */
  static String git(Path cwd, String... command) throws Exception {
    Result result = run(cwd, command);
    if (result.exit() != 0) {
      throw new RuntimeException("git " + String.join(" ", command) + " failed:\n" + result.out());
    }
    return result.out();
  }

  /**
   * Runs git expecting it to fail, and returns what it printed. A refused push is the subject of
   * half these tests, so its output is a value rather than an exception — the message the pusher
   * reads is exactly what is being asserted.
   */
  static String gitExpectingFailure(Path cwd, String... command) throws Exception {
    Result result = run(cwd, command);
    if (result.exit() == 0) {
      fail("git " + String.join(" ", command) + " unexpectedly succeeded:\n" + result.out());
    }
    return result.out();
  }

  private record Result(int exit, String out) {}

  private static Result run(Path cwd, String... command) throws Exception {
    return run(cwd, Map.of(), command);
  }

  private static Result run(Path cwd, Map<String, String> environment, String... command)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.environment().putAll(environment);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    return new Result(p.waitFor(), out);
  }
}
