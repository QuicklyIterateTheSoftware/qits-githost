package eu.wohlben.qits.githost.stories.support;

import eu.wohlben.qits.servicemock.MockService;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * qits-projects, played by a recording {@link MockService} — the far end of the one lookup that
 * makes a PUBLIC clone url serve at all.
 *
 * <p>This host holds no name for any repository: it keys them by the id qits-projects mints, and
 * {@code /git/:projectId/:repoName} works only because {@code HttpRepositoryNameResolver} asks
 * qits-projects to turn the pair into that id, over
 *
 * <pre>
 *   GET &lt;qits.projects.name-resolver-url&gt;/{projectId}/repositories/by-name/{repoName}
 *   200 -&gt; {"repositoryId": "&lt;id&gt;"}
 * </pre>
 *
 * <p>So every git story's diagram carries two hops rather than one: the developer reaching this
 * service, and this service reaching qits-projects. The second is <b>observed on the far side</b>
 * — {@link #installSource()} registers the mock's own recording as a {@link NetworkCapture} source,
 * exactly as {@code TokenValidationBootstrapIT} does for the idp — so the story claims nothing and
 * the edge carries the status this side actually answered.
 *
 * <h2>Why the aliases are a fixed table</h2>
 *
 * <p>The stubs must exist before the launched process serves its first request, so they are
 * registered from the {@code QuarkusTestProfile} that hands the process its resolver url. A profile
 * is instantiated in more than one classloader and cannot carry per-run state between them, so the
 * alias table is <b>static</b> and its storage ids are readable literals rather than generated
 * UUIDs. That costs nothing a UUID would buy: {@link StoryOrigin} deletes and recreates each
 * repository before its story, so a fixed id is a repository at a known, empty state rather than
 * whatever a previous build left — and a readable id keeps the browser plane's labels
 * ({@code GET /githost/api/repositories/story-browse -> 200}) legible in the diagram, where a UUID
 * would only ever render as {@code {id}}.
 */
public final class MockProjects {

  /** How the diagram names the service this mock impersonates. */
  public static final String SERVICE_NAME = "qits-projects";

  /**
   * The path qits-projects answers the lookup under, up to but not including {@code /{projectId}}.
   * It is qits-projects' own path, exactly as a deployment spells it
   * ({@code http://<env>-qits-projects:8080/projects/api/projects}); only the host part is a
   * deployment decision.
   */
  public static final String RESOLVER_PATH = "/projects/api/projects";

  /**
   * Every {@code repoName -> repoId} alias the story catalogue needs. One repository per story, so
   * no story can be reading what another one pushed — the diagrams stay legible and a class runs
   * green on its own.
   */
  public static final Map<String, String> ALIASES = aliases();

  /**
   * The one storage id with <b>no alias</b>, and deliberately: the browser plane addresses
   * repositories by storage id ({@code /githost/api/repositories/{repoId}}) because the SPA has
   * already resolved the public {@code (project, repoName)} spelling against qits-projects
   * client-side. So the browse story asks this service for no lookup at all — which is a claim its
   * diagram makes by having no {@code qits-githost -> qits-projects} edge in it.
   */
  public static final String BROWSE_REPOSITORY_ID = "story-browse";

  /** Set once the owning copy of this class has registered the stubs; see {@link #ensureStarted}. */
  private static final String STUBBED_PROPERTY = "qits.stories.mock-projects.stubbed";

  /** The id this class registers its recording under; re-registering keeps the drained cursor. */
  private static final String SOURCE_ID = "mock-projects";

  private MockProjects() {}

  private static Map<String, String> aliases() {
    Map<String, String> table = new LinkedHashMap<>();
    table.put("hello-world", "story-clone");
    table.put("release-notes", "story-push");
    table.put("shared-notes", "story-pull");
    table.put("pipeline-config", "story-read");
    return Map.copyOf(table);
  }

  /** The storage id behind a public repository name — what qits-projects answers, as this test knows it. */
  public static String repositoryId(String repoName) {
    String id = ALIASES.get(repoName);
    if (id == null) {
      throw new IllegalArgumentException(
          "no alias for " + repoName + "; add it to MockProjects.ALIASES so the profile stubs it");
    }
    return id;
  }

  /**
   * Start the mock once per JVM and stub every alias, then hand back a handle.
   *
   * <p>{@link MockService#ensureStarted} parks the port in a system property so a second
   * classloader's copy attaches to the same server rather than starting a second one. Only the
   * copy that actually <i>started</i> it may stub, and there is no {@code isOwner()} to ask — so
   * the stubbing is guarded by a property of its own, written by whoever got there first. Quarkus
   * instantiates profiles sequentially, so this is an ordering guard rather than a lock.
   */
  public static synchronized MockService ensureStarted() {
    MockService projects = MockService.ensureStarted(SERVICE_NAME);
    if (System.getProperty(STUBBED_PROPERTY) == null) {
      ALIASES.forEach(
          (name, id) ->
              projects.stub("GET", lookupPath(name), Map.of("repositoryId", id)));
      System.setProperty(STUBBED_PROPERTY, "true");
    }
    return projects;
  }

  /** A handle onto the running mock, from any classloader. */
  public static MockService attach() {
    return MockService.attach(SERVICE_NAME);
  }

  /**
   * What the launched process is told to dial: {@code qits.projects.name-resolver-url}. Absent
   * config is a supported configuration in which the name-addressed scheme simply 404s — which is
   * to say, without this key not one story here would clone anything.
   */
  public static String resolverUrl() {
    return ensureStarted().baseUrl() + RESOLVER_PATH;
  }

  /** The path one lookup arrives on — the label half a network assertion has to spell. */
  public static String lookupPath(String repoName) {
    return RESOLVER_PATH + "/" + StoryTarget.PROJECT + "/repositories/by-name/" + repoName;
  }

  /** The label an answered lookup renders as, once the framework has scrubbed it. */
  public static String lookupLabel(String repoName, int status) {
    return "GET " + lookupPath(repoName) + " -> " + status;
  }

  /**
   * Register the mock's recording as a cumulative {@link NetworkCapture} source — the far half of
   * every git story's diagram.
   *
   * <p>Called from a story class's {@code @BeforeAll}. Re-registering under the same id replaces
   * the supplier and <b>keeps its cursor</b>, so several story classes may each install it without
   * any story re-drawing an earlier one's lookups. It is invoked lazily at story end, so
   * registering before anything has been recorded is safe.
   */
  public static void installSource() {
    NetworkCapture.source(
        SOURCE_ID,
        () ->
            attach().recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            StoryAccessLog.SERVICE,
                            SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
  }
}
