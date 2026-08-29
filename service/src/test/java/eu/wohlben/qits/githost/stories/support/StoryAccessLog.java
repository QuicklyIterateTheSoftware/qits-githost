package eu.wohlben.qits.githost.stories.support;

import eu.wohlben.qits.userflows.Labels;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The <b>incoming</b> tap for the command-line stories: the launched process' own access log, read
 * back as {@link NetworkCapture} edges.
 *
 * <p>A RestAssured filter cannot serve these stories the way it serves {@code
 * TokenValidationBootstrapIT}. The subject here is a <b>real external tool</b> — {@code git clone},
 * {@code git push}, {@code curl} — talking to the packaged process over a socket this JVM never
 * touches. Nothing in the test process is on that path, so the only place the traffic exists is the
 * server's own record of it. {@code quarkus.http.access-log.*} is that record; {@link
 * #configOverrides()} is the whole configuration and {@code TokenValidationBootstrapIT
 * .PackagedWithMockIdp} is the one profile that installs it.
 *
 * <h2>The pattern, and what each token actually yields</h2>
 *
 * <p>{@code %m %U %s} — method, requested URL, response status. <b>{@code %U} is {@code
 * HttpServerRequest.uri()}</b>, so it carries the query string as well as the path; that is
 * deliberate rather than tolerated, because the query is what says which git service a request
 * belongs to ({@code /info/refs?service=git-upload-pack} versus {@code ?service=git-receive-pack})
 * and which revision a browser read asked for. A path-only token ({@code %R}) would collapse a
 * clone and a push into the same edge.
 *
 * <h2>Two kinds, decided per line</h2>
 *
 * <p>The kind vocabulary is open, and this service serves two genuinely different things on one
 * port. The three smart-HTTP shapes — the ref advertisement and the two pack verbs — are the
 * <b>git protocol</b>, a negotiated multi-request exchange whose transport happening to be HTTP
 * says nothing about what it means, so they are recorded as {@code git}. Everything else — a
 * content read at {@code …/blob/…}, the browser plane's JSON — is a plain request and is recorded
 * as {@code http}. The classification is per LINE rather than per story, so a story that clones and
 * then reads a file back gets both kinds in one diagram.
 *
 * <h2>Attribution: an actor per line, switched by the story</h2>
 *
 * <p>A cumulative source is read lazily at story end, and the framework's per-source cursor hands
 * each recorded entry to exactly one story. The obvious implementation reads {@link
 * NetworkCapture#actor()} at <i>drain</i> time and therefore gives a story ONE initiator for every
 * line it drains — which is wrong for the pull story, where a teammate pushes and a developer
 * pulls in the same walk.
 *
 * <p>So the lines are <b>stamped as they are harvested</b>, not as they are drained: {@link
 * #actor(String)} first turns every line written so far into an edge under the OUTGOING actor, and
 * only then switches. A story therefore names its initiator before its first request and again at
 * every hand-over, and the accumulated edge list — which is what the supplier returns, whole, every
 * time — carries each line under whoever actually made it. Call {@link #awaitLogged} before a
 * hand-over: a line still in flight when the actor switches would be stamped with the wrong one.
 *
 * <p>{@link NetworkCapture} resets its own actor at every story border but cannot reset this
 * class's, so <b>a story that names no actor inherits the previous story's</b>. Every story here
 * calls {@link #actor(String)} before it does anything.
 *
 * <h2>What is NOT a story's traffic</h2>
 *
 * <p>Three exclusions, and each one is a line nobody wrote a story about:
 *
 * <ul>
 *   <li><b>Everything already in the file.</b> The log is append-only across the whole IT phase and
 *       survives between builds, so {@link #install()} registers a <b>floor</b> — every line
 *       present when the first story class starts belongs to an earlier build or to
 *       {@code TokenValidationBootstrapIT}, which taps itself through RestAssured and would
 *       otherwise be counted twice.
 *   <li><b>Probes.</b> This service's non-application root is {@code /githost/q}, so the check is
 *       on the {@code /q/} <b>segment</b> rather than a prefix, and a diagram in which every node
 *       hangs off {@code /githost/q/health/ready} never happens.
 *   <li><b>The storage plane.</b> {@code /git/:repoId} and its three smart-HTTP shapes are
 *       qits-projects' own client's wire, and in these stories that client is {@link StoryOrigin}
 *       provisioning and seeding a fixture. It is setup, not a walk anybody takes — and the
 *       distinction is the service's own: the PUBLIC scheme carries one more path segment, so the
 *       two never collide here any more than they do in the router.
 * </ul>
 */
public final class StoryAccessLog {

  /** The {@code to} of every edge this tap observes — the launched process, as a diagram names it. */
  public static final String SERVICE = "qits-githost";

  /** One registration per JVM; re-registering under this id would keep the cursor anyway. */
  private static final String SOURCE_ID = "access-log";

  /**
   * The file name halves. Quarkus resolves the access log as {@code
   * <log-directory>/<base-file-name><log-suffix>} and {@code rotate=false} keeps it at that one
   * name for the life of the build — a rotated file would leave the tail of a run in a sibling this
   * class never reads.
   */
  private static final String BASE_FILE_NAME = "story-access";

  private static final String LOG_SUFFIX = ".log";

  /**
   * The internal storage scheme, exactly: the lifecycle path and its three smart-HTTP shapes. The
   * PUBLIC scheme ({@code /git/:projectId/:repoName/…}) carries one more segment and {@code [^/]+}
   * cannot swallow a slash, so nothing a story drives is matched here.
   */
  private static final Pattern STORAGE_PLANE =
      Pattern.compile("/git/[^/]+(/info/refs|/git-upload-pack|/git-receive-pack)?");

  /** The three shapes that ARE the git protocol; everything else this host serves is plain HTTP. */
  private static final List<String> GIT_PROTOCOL =
      List.of("/info/refs", "/git-upload-pack", "/git-receive-pack");

  /**
   * How long {@link #awaitLogged} waits for a line to reach disk. The receiver writes on its own
   * executor and flushes per batch, so the gap between a tool's response and the line existing is
   * milliseconds — this is a ceiling, not a budget.
   */
  private static final Duration FLUSH_PATIENCE = Duration.ofSeconds(5);

  private static final long POLL_MILLIS = 25;

  private static final Object LOCK = new Object();

  private static boolean registered;

  /** How many lines the file already held when the first story class installed the tap. */
  private static int floor;

  /** How many lines have already been turned into edges — the harvest cursor. */
  private static int harvested;

  /** Every edge harvested so far, in arrival order: the cumulative recording the supplier returns. */
  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  /** Who the lines harvested from now on belong to. */
  private static String actor = "a caller";

  private StoryAccessLog() {}

  // --- configuration ---------------------------------------------------------------------------

  /**
   * Where the launched process writes, as an <b>absolute</b> path. The process is started with a
   * working directory this suite does not choose, so a relative {@code log-directory} would put the
   * file somewhere nothing here could find; and it sits under {@code target/} so a {@code clean}
   * takes it.
   */
  public static Path logDirectory() {
    return Path.of(System.getProperty("user.dir"), "target", "story-access-log").toAbsolutePath();
  }

  /** The single file {@link #configOverrides()} configures and this class reads. */
  public static Path logFile() {
    return logDirectory().resolve(BASE_FILE_NAME + LOG_SUFFIX);
  }

  /**
   * The access-log block a launched process needs for these stories to have a diagram at all. Every
   * key is <b>runtime</b> configuration ({@code VertxHttpConfig}, not {@code
   * VertxHttpBuildTimeConfig}), so it reaches an already-built artifact as a {@code -D} flag and
   * nothing re-augments.
   */
  public static Map<String, String> configOverrides() {
    try {
      Files.createDirectories(logDirectory());
    } catch (IOException unwritable) {
      throw new IllegalStateException("cannot create " + logDirectory(), unwritable);
    }
    Map<String, String> overrides = new LinkedHashMap<>();
    overrides.put("quarkus.http.access-log.enabled", "true");
    overrides.put("quarkus.http.access-log.log-to-file", "true");
    overrides.put("quarkus.http.access-log.pattern", "%m %U %s");
    overrides.put("quarkus.http.access-log.log-directory", logDirectory().toString());
    overrides.put("quarkus.http.access-log.base-file-name", BASE_FILE_NAME);
    overrides.put("quarkus.http.access-log.log-suffix", LOG_SUFFIX);
    overrides.put("quarkus.http.access-log.rotate", "false");
    return overrides;
  }

  // --- what a story class calls ------------------------------------------------------------------

  /**
   * Register the tap once per JVM, taking the current end of the file as the floor. Called from
   * every story class's {@code @BeforeAll}; the first one to run is what bounds what any story can
   * see.
   */
  public static void install() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      floor = readLines().size();
      harvested = 0;
      NetworkCapture.source(SOURCE_ID, StoryAccessLog::edges);
      registered = true;
    }
  }

  /**
   * Name who is about to make requests. Everything logged up to this moment is stamped with the
   * previous initiator first, so a story may hand over mid-walk — which is what the pull story
   * needs, and what a drain-time read of {@link NetworkCapture#actor()} cannot express.
   *
   * <p>{@link NetworkCapture#actor(String)} is set to the same name, so a RestAssured call made by
   * a story in this fork — {@code TokenValidationBootstrapIT}'s tap is installed JVM-wide — would
   * agree with this one rather than contradict it.
   */
  public static void actor(String name) {
    synchronized (LOCK) {
      harvest();
      actor = name;
    }
    NetworkCapture.actor(name);
  }

  /**
   * Wait, briefly and without asserting anything, for a line containing {@code fragment} to reach
   * the log file.
   *
   * <p>The receiver writes off the request thread, so a tool's response can be back before the line
   * is on disk — and a line that lands after the story's drain is a line in the <i>next</i> story's
   * diagram, while one that lands after an {@link #actor(String)} hand-over is a line under the
   * wrong initiator. A story therefore calls this once its last interesting request has answered,
   * and again before every hand-over.
   *
   * <p>Deliberately silent on timeout: this is a latency hedge, not a proof. The proof is the
   * {@code assertEdge} in the class's {@code @AfterAll}, and a failure there says which edge is
   * missing, which a timeout here would only obscure.
   */
  public static void awaitLogged(String fragment) {
    long deadline = System.nanoTime() + FLUSH_PATIENCE.toNanos();
    while (true) {
      for (String line : readLines()) {
        if (line.contains(fragment)) {
          return;
        }
      }
      if (System.nanoTime() >= deadline) {
        return;
      }
      try {
        Thread.sleep(POLL_MILLIS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  // --- the source ---------------------------------------------------------------------------------

  /**
   * The whole recording, every time — the contract {@link NetworkCapture#source} states, with the
   * cursor deciding which slice of it belongs to the story now draining. Harvesting first is what
   * puts the last few lines of the current story in the current story.
   */
  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      harvest();
      return List.copyOf(EDGES);
    }
  }

  /**
   * Turn every line written since the last harvest into an edge under the current actor. The
   * accumulated list only ever grows and never rewrites what it already holds, which is exactly
   * what a cumulative source's cursor requires: a skipped line is never in it, so a skip cannot
   * shift an earlier story's slice.
   */
  private static void harvest() {
    List<String> lines = readLines();
    if (harvested > lines.size()) {
      // The file was truncated under us (a `clean` mid-run). Start over rather than mis-slice.
      harvested = 0;
      floor = 0;
    }
    for (String line : lines.subList(harvested, lines.size())) {
      edge(line).ifPresent(EDGES::add);
    }
    harvested = lines.size();
  }

  /** One log line as an edge, or nothing if it is not a story's traffic. */
  private static Optional<NetworkEdge> edge(String line) {
    // "%m %U %s" — three fields, no quoting, and a URI can carry no raw space.
    String[] fields = line.strip().split(" ");
    if (fields.length != 3) {
      return Optional.empty();
    }
    String method = fields[0];
    String uri = fields[1];
    String status = fields[2];
    // An attribute the handler could not resolve is written as "-"; such a line describes no
    // request anybody made and is not an edge.
    if (!uri.startsWith("/") || !status.chars().allMatch(Character::isDigit)) {
      return Optional.empty();
    }
    String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
    if (path.contains("/q/") || STORAGE_PLANE.matcher(path).matches()) {
      return Optional.empty();
    }
    String kind = GIT_PROTOCOL.stream().anyMatch(path::endsWith) ? "git" : NetworkEdge.HTTP;
    return Optional.of(
        new NetworkEdge(kind, actor, SERVICE, method + " " + Labels.scrub(uri) + " -> " + status));
  }

  /** Everything logged since the floor — i.e. everything a story could own. */
  private static List<String> readLines() {
    List<String> all = allLines();
    return floor >= all.size() ? List.of() : all.subList(floor, all.size());
  }

  /**
   * The log file's complete lines. A missing file is an empty recording rather than a failure — the
   * suite must stay green on a machine that skipped every CLI story — and an <b>unterminated tail
   * is dropped</b>, because the writer is appending while this reads and half a line would shape
   * half an edge. The next harvest sees it whole.
   */
  private static List<String> allLines() {
    Path file = logFile();
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    return List.of(text.substring(0, lastComplete).split("\n"));
  }
}
