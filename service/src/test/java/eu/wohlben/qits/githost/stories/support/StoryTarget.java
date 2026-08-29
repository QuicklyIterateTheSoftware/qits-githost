package eu.wohlben.qits.githost.stories.support;

import java.net.URL;

/**
 * The one launched process, addressed the way each of its planes is addressed.
 *
 * <p>A story receives its root URL from {@code @TestHTTPResource("/")} — a {@code localhost} URL on
 * a <b>random</b> port, because the packaged process is launched with {@code
 * quarkus.http.test-port=0}. That randomness is the reason this class exists: a URL built here is
 * always passed to {@link eu.wohlben.qits.userflows.Commands#run} as an <b>argument</b>, never
 * spelled into a template, so the recorded fingerprint stays {@code {}} and the story's definition
 * hash survives the port changing on every run.
 *
 * <h2>The two git addressing schemes, and which one a story may use</h2>
 *
 * <p>This host serves the same repository under two spellings, and they are not interchangeable:
 *
 * <ul>
 *   <li><b>{@code /git/:projectId/:repoName}</b> — the PUBLIC clone url. It is what a developer
 *       types, what a workspace container's remote holds, what every CI checkout dials and what
 *       makes a committed relative submodule url ({@code ../<name>.git}) resolve. Serving it means
 *       asking qits-projects to turn the name into a storage id, which is why every story using it
 *       puts a {@code qits-githost -> qits-projects} edge in its diagram. <b>Every story here uses
 *       this scheme</b>, because it is the one a user meets.
 *   <li><b>{@code /git/:repoId}</b> — the internal storage scheme, plus the lifecycle
 *       {@code PUT}/{@code GET}/{@code HEAD}/{@code DELETE} that only exist there. This host keys
 *       repositories by the id qits-projects mints and holds no name for any of them; a caller
 *       holding one of those urls is qits-projects' own client and nothing else. <b>Only {@link
 *       StoryOrigin} uses this scheme</b>, standing in for that client, and {@link StoryAccessLog}
 *       drops every request on it: provisioning a fixture is not a story's traffic.
 * </ul>
 *
 * <p>Each shape exists twice below and both come off the same constant. The <b>URL</b> accessors
 * are what a story hands a tool; the <b>PATH</b> helpers are what the launched process writes into
 * its access log, and therefore what a network assertion in a static {@code @AfterAll} — where no
 * instance and no port exist — has to spell. Deriving one from the other is the point: a wire that
 * moves moves in both places at once.
 */
public final class StoryTarget {

  /**
   * {@code /git} — the git wire's root. A literal in {@code GitHostRoutes} rather than a config
   * key, because git treats the base as opaque and appends {@code /info/refs} and the two pack
   * verbs itself; no key can move it and moving it would be a cutover of every clone url on the
   * platform.
   */
  public static final String GIT_PATH = "/git";

  /**
   * {@code /githost/api/repositories} — the browser plane, under {@code quarkus.rest.path}. The
   * catalogue itself is anonymous (it answers opaque ids); everything beneath it serves file
   * CONTENTS and is role-gated by the {@code githost-browse} policy.
   */
  public static final String API_PATH = "/githost/api/repositories";

  /**
   * The project every story's repository lives under. A readable id rather than a UUID, because
   * that is what the platform's own wrapper uses ({@code projectId=qits} in the release door) and
   * because a public clone url is meant to be typed.
   */
  public static final String PROJECT = "qits";

  /** Always with a trailing slash, so every accessor below is a plain concatenation. */
  private final String root;

  public StoryTarget(URL root) {
    this(root.toString());
  }

  public StoryTarget(String root) {
    this.root = root.endsWith("/") ? root : root + "/";
  }

  /** The host root — where the SPA is served. */
  public String root() {
    return root;
  }

  // --- the public scheme, which is what the stories drive ----------------------------------------

  /** The clone url a developer types: {@code <root>/git/qits/<repoName>}. */
  public String cloneUrl(String repoName) {
    return root + clonePath(repoName).substring(1);
  }

  /** {@code /git/qits/<repoName>} — the path the access log records for every wire request. */
  public static String clonePath(String repoName) {
    return GIT_PATH + "/" + PROJECT + "/" + repoName;
  }

  /**
   * One file at one revision, read over plain HTTP and without cloning anything:
   * {@code /git/qits/<repoName>/blob/<rev>/<path>}. This is how qits-ci reads a pipeline
   * definition and how the workspace daemon reads a single file.
   */
  public String blobUrl(String repoName, String rev, String path) {
    return root + blobPath(repoName, rev, path).substring(1);
  }

  public static String blobPath(String repoName, String rev, String path) {
    return clonePath(repoName) + "/blob/" + rev + "/" + path;
  }

  /** The tree listing at one revision: {@code /git/qits/<repoName>/tree/<rev>}. */
  public String treeUrl(String repoName, String rev) {
    return root + treePath(repoName, rev).substring(1);
  }

  public static String treePath(String repoName, String rev) {
    return clonePath(repoName) + "/tree/" + rev;
  }

  // --- the browser plane -------------------------------------------------------------------------

  /** {@code /githost/api/repositories} — the anonymous catalogue of storage ids. */
  public String catalogueUrl() {
    return root + API_PATH.substring(1);
  }

  /** {@code /githost/api/repositories/<repoId>} — one repository, described. */
  public String describeUrl(String repoId) {
    return root + describePath(repoId).substring(1);
  }

  public static String describePath(String repoId) {
    return API_PATH + "/" + repoId;
  }

  /** The whole tree at one revision, as the SPA's Code page asks for it. */
  public String browseTreeUrl(String repoId, String rev) {
    return describeUrl(repoId) + "/tree?rev=" + rev;
  }

  public static String browseTreePath(String repoId, String rev) {
    return describePath(repoId) + "/tree?rev=" + rev;
  }

  /** One file, as a record that says {@code binary} instead of failing on a big blob. */
  public String browseFileUrl(String repoId, String rev, String path) {
    return describeUrl(repoId) + "/file?rev=" + rev + "&path=" + path;
  }

  public static String browseFilePath(String repoId, String rev, String path) {
    return describePath(repoId) + "/file?rev=" + rev + "&path=" + path;
  }

  // --- the storage scheme, for the fixture only --------------------------------------------------

  /**
   * {@code <root>/git/<repoId>} — the internal storage url {@link StoryOrigin} provisions and seeds
   * through. A story must not spell this: see the class javadoc.
   */
  public String storageUrl(String repoId) {
    return root + GIT_PATH.substring(1) + "/" + repoId;
  }
}
