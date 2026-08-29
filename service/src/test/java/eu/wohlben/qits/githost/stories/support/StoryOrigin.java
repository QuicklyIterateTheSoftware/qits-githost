package eu.wohlben.qits.githost.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixture behind every git story: a repository that exists, at a state the story knows.
 *
 * <p>It plays <b>qits-projects' own client</b>, which is the only caller with a legitimate reason
 * to speak storage ids — so everything here goes over the id-addressed scheme ({@code
 * /git/:repoId}) while every story goes over the public one ({@code /git/:projectId/:repoName}).
 * That split is what lets {@link StoryAccessLog} drop this traffic without a heuristic: the two
 * schemes differ by a path segment, which is the same fact the router dispatches on.
 *
 * <p><b>Nothing here is a story step, and nothing here is recorded.</b> Provisioning is driven with
 * the JDK's {@link HttpClient} and a bare {@link ProcessBuilder} rather than with {@code Commands}
 * or RestAssured — the first would put setup in the transcript, the second would be caught by the
 * RestAssured tap {@code TokenValidationBootstrapIT} installs JVM-wide and drawn as an edge nobody
 * walked.
 *
 * <p><b>Delete, then create.</b> The storage ids are fixed literals (see {@link MockProjects}), and
 * the IT database is not wiped between builds, so a repository left by a previous run would make
 * "a push lands NEW history" a push of history that was already there. {@code DELETE} takes every
 * row keyed by the id — packs, pack files, the protection override, the loc memos — in one
 * transaction, and the {@code PUT} that follows is a repository whose HEAD names an unborn branch
 * and nothing else.
 */
public final class StoryOrigin {

  /**
   * The audience the packaged process enforces. Spelled here rather than imported from the IT's
   * profile so this class stays usable from any story class; the two are asserted to agree by the
   * simple fact that a wrong one makes every story 401.
   */
  public static final String AUDIENCE = "dev-qits-githost";

  /** The default branch every story's origin carries — pinned, never left to {@code init.defaultBranch}. */
  public static final String BRANCH = "main";

  /** The identity the fixture commits as, so no run depends on a developer's global git config. */
  private static final String COMMITTER_EMAIL = "qits-projects@qits.local";

  private static final String COMMITTER_NAME = "qits-projects";

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private StoryOrigin() {}

  // --- credentials --------------------------------------------------------------------------------

  /**
   * A bearer the git wire accepts: minted by the mock idp, for this service's audience, carrying
   * {@code qits:system} in {@code groups} — the shape a platform service's token has, and what the
   * {@code git} path policy is configured to admit. Validation is real: the packaged process
   * fetched this idp's JWKS at startup and checks the signature and the audience against it.
   */
  public static String bearer() {
    return bearer("qits:system");
  }

  /**
   * The same, with the roles spelled out — {@code qits:admin} for the browser plane, whose policy
   * ({@code githost-browse}) is what a session carries rather than what a machine does.
   */
  public static String bearer(String... groups) {
    return MockIdp.attach()
        .token()
        .subject("qits-stories")
        .audience(AUDIENCE)
        .groups(groups)
        .mint();
  }

  /**
   * The one git config a story sets: {@code http.extraHeader=Authorization: Bearer <token>}. Passed
   * to {@code git -c} as a single argument, which is why a story spells it as a {@code {}} value —
   * it holds spaces, and {@code Commands.run} tokenizes the template before filling it, so a value
   * is always exactly one argv element however many spaces it carries.
   */
  public static String authConfig(String token) {
    return "http.extraHeader=Authorization: Bearer " + token;
  }

  // --- provisioning --------------------------------------------------------------------------------

  /**
   * A repository that exists and is empty: {@code DELETE} whatever is there, then {@code PUT} it
   * back with a HEAD naming {@link #BRANCH} and nothing on it. This is the shape a freshly
   * provisioned origin has.
   */
  public static void provisionEmpty(StoryTarget target, String repoId, String token) {
    String url = target.storageUrl(repoId);
    send(request(url, token).DELETE().build(), 204, 404);
    send(
        request(url, token)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"defaultBranch\":\"" + BRANCH + "\"}"))
            .build(),
        201);
  }

  /**
   * {@link #provisionEmpty} plus one commit, pushed over the storage wire — because receive-pack is
   * the only writer this storage has: a repository's packs and refs are content-addressed blobs, so
   * there is no bare directory anywhere for anything else to write into.
   *
   * @return the sha of the seeded commit, which is what a story compares its clone's HEAD against.
   */
  public static String provisionSeeded(
      StoryTarget target, String repoId, String token, Map<String, String> files, String message) {
    provisionEmpty(target, repoId, token);
    Path seed = scratch("qits-story-seed");
    git(seed, "init", "-q", "-b", BRANCH, ".");
    files.forEach((path, content) -> write(seed, path, content));
    git(seed, "add", "-A");
    git(
        seed,
        "-c",
        "user.email=" + COMMITTER_EMAIL,
        "-c",
        "user.name=" + COMMITTER_NAME,
        "commit",
        "-q",
        "-m",
        message);
    git(seed, "-c", authConfig(token), "push", "-q", target.storageUrl(repoId), BRANCH);
    return git(seed, "rev-parse", "HEAD").trim();
  }

  // --- the wire -------------------------------------------------------------------------------------

  private static HttpRequest.Builder request(String url, String token) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(TIMEOUT)
        .header("Authorization", "Bearer " + token);
  }

  private static void send(HttpRequest request, int... accepted) {
    try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      for (int status : accepted) {
        if (response.statusCode() == status) {
          return;
        }
      }
      throw new IllegalStateException(
          request.method()
              + " "
              + request.uri()
              + " answered "
              + response.statusCode()
              + ": "
              + response.body());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(request.uri() + " interrupted", interrupted);
    } catch (IOException e) {
      throw new IllegalStateException(request.uri() + " failed", e);
    }
  }

  // --- the tool ---------------------------------------------------------------------------------------

  /**
   * Runs git in {@code cwd} with a hermetic environment — its own {@code HOME}, no system config,
   * no credential prompt — and fails loudly with the captured output. The fixture shells the real
   * client for the same reason the stories do: what is under test is whether it can talk to this
   * host, and a DFS-backed repository has no directory for anything else to open.
   */
  private static String git(Path cwd, String... args) {
    List<String> command = new ArrayList<>();
    command.add(StoryTools.git());
    command.addAll(List.of(args));
    Map<String, String> environment = new LinkedHashMap<>();
    environment.put("HOME", cwd.toAbsolutePath().toString());
    environment.put("GIT_CONFIG_NOSYSTEM", "1");
    environment.put("GIT_TERMINAL_PROMPT", "0");
    try {
      ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
      builder.environment().putAll(environment);
      builder.redirectErrorStream(true);
      Process process = builder.start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      if (exit != 0) {
        throw new IllegalStateException(
            "fixture: " + String.join(" ", command) + " exited " + exit + ":\n" + output);
      }
      return output;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("fixture git interrupted", interrupted);
    } catch (IOException e) {
      throw new IllegalStateException("fixture git failed", e);
    }
  }

  private static Path scratch(String prefix) {
    try {
      return Files.createTempDirectory(prefix);
    } catch (IOException e) {
      throw new IllegalStateException("cannot create a scratch directory", e);
    }
  }

  private static void write(Path root, String relativePath, String content) {
    try {
      Path file = root.resolve(relativePath);
      Files.createDirectories(file.getParent());
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("cannot write " + relativePath, e);
    }
  }
}
