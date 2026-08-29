package eu.wohlben.qits.githost.stories.browse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.githost.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.githost.stories.support.MockProjects;
import eu.wohlben.qits.githost.stories.support.StoryAccessLog;
import eu.wohlben.qits.githost.stories.support.StoryOrigin;
import eu.wohlben.qits.githost.stories.support.StoryTarget;
import eu.wohlben.qits.githost.stories.support.StoryTools;
import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The other reader this host has: a person with a browser, on the Code page of qits-spa-githost.
 *
 * <p>Its own category, because it is a different plane with a different contract. The git wire
 * answers a protocol; this answers <b>records</b> — a repository's branches, a whole tree in one
 * read, and a file as a document that says {@code binary} and a real {@code size} rather than
 * failing with a 413. It also addresses repositories by <b>storage id</b>: the SPA has already
 * resolved the public {@code (project, repoName)} spelling against qits-projects client-side, so
 * this service is asked no lookup at all — which is a claim the diagram makes by having no
 * {@code qits-githost -> qits-projects} edge in it, and one {@code assertOnlyEdgesFrom} pins.
 *
 * <p>The catalogue at the collection path is the deliberately anonymous exception (it answers
 * opaque ids and nothing else); everything beneath it serves file CONTENTS and is gated by the
 * {@code githost-browse} policy. The story presents a {@code qits:admin} bearer throughout, which
 * is the role a browser session carries — in a deployment through the edge's forwarded headers,
 * here as a token the mock idp minted, because a packaged process running in {@code NORMAL} mode
 * deliberately stays anonymous to a header nobody vouched for.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@EnabledIf("eu.wohlben.qits.githost.stories.support.StoryTools#curlPresent")
public class CodeBrowseIT {

  static final String CATEGORY = "browse";
  static final String SLUG = "a-reader-opens-a-file-in-the-code-browser";

  static final String ACTOR = "a reader in the browser";

  /** Addressed by storage id, because that is what the browser plane speaks. */
  static final String REPO_ID = MockProjects.BROWSE_REPOSITORY_ID;

  static final String FILE = "README.md";

  static final String FILE_CONTENT = "# product-docs\n\nThe page a reader opens.\n";

  static final String SOURCE_FILE = "src/main.js";

  private static String token;

  @TestHTTPResource("/")
  URL root;

  @BeforeAll
  static void tapTheNetwork() {
    StoryAccessLog.install();
    // Registered although this story expects no lookup: the source has to be draining for
    // "qits-projects was never asked" to be an observation rather than an omission.
    MockProjects.installSource();
  }

  @BeforeEach
  void theProjectHasARepository() {
    Map<String, String> seed = new LinkedHashMap<>();
    seed.put(FILE, FILE_CONTENT);
    seed.put(SOURCE_FILE, "export const greeting = 'hello';\n");
    // Provisioned with a machine bearer, read with a browser one — the two roles this service
    // distinguishes, exercised as the two different callers they are.
    StoryOrigin.provisionSeeded(
        new StoryTarget(root), REPO_ID, StoryOrigin.bearer(), seed, "the first commit");
    token = StoryOrigin.bearer("qits:admin");
  }

  @UserStory(value = "A reader opens a file in the code browser", category = "browse")
  @UserStoryDescription(
      """
      Somebody opens the git host in a browser and reads a file. Four requests do it: the
      catalogue of repositories this host holds, the repository itself (its default branch and
      every branch it carries), the whole tree at one revision in a single read, and the file as a
      record with its content. The page never asks qits-projects anything — it resolved the
      repository's name before it got here — so this plane's dependency graph is one arrow wide,
      which is exactly what the story asserts.
      """)
  void aReaderOpensAFileInTheCodeBrowser(Interactions story, Commands commands) {
    StoryTarget target = new StoryTarget(root);
    commands.redact(token);
    StoryAccessLog.actor(ACTOR);

    story
        .note("the page has already resolved the repository's name to a storage id")
        .as("page-opened");

    commands.run("{} -sS -f -H {} {}", StoryTools.curl(), authorization(), target.catalogueUrl())
        .as("catalogue-read");
    assertTrue(
        commands.lastOutput().contains("\"id\":\"" + REPO_ID + "\""),
        () -> "the catalogue must list the repository: " + commands.lastOutput());

    commands
        .run("{} -sS -f -H {} {}", StoryTools.curl(), authorization(), target.describeUrl(REPO_ID))
        .as("repository-described");
    assertTrue(
        commands.lastOutput().contains("\"defaultBranch\":\"" + StoryOrigin.BRANCH + "\""),
        () -> "the description must name the default branch: " + commands.lastOutput());

    commands
        .run(
            "{} -sS -f -H {} {}",
            StoryTools.curl(),
            authorization(),
            target.browseTreeUrl(REPO_ID, StoryOrigin.BRANCH))
        .as("tree-listed");
    assertTrue(
        commands.lastOutput().contains(SOURCE_FILE) && commands.lastOutput().contains(FILE),
        () -> "one read must carry every path in the tree: " + commands.lastOutput());

    commands
        .run(
            "{} -sS -f -H {} {}",
            StoryTools.curl(),
            authorization(),
            target.browseFileUrl(REPO_ID, StoryOrigin.BRANCH, FILE))
        .as("file-opened");
    assertTrue(
        commands.lastOutput().contains("\"binary\":false"),
        () -> "a text file must come back as text: " + commands.lastOutput());
    assertTrue(
        commands.lastOutput().contains("The page a reader opens."),
        () -> "…with its content in the record: " + commands.lastOutput());

    story
        .note("the reader is looking at the file's content, four requests after opening the page")
        .as("file-displayed");

    StoryAccessLog.awaitLogged(
        "GET " + StoryTarget.browseFilePath(REPO_ID, StoryOrigin.BRANCH, FILE));
  }

  /** The header a browser session's request carries, as one argv element. */
  private static String authorization() {
    return "Authorization: Bearer " + token;
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!StoryTools.curlPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "page-opened");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "catalogue-read");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repository-described");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "tree-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "file-opened");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "file-displayed");

    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "http",
        ACTOR,
        StoryAccessLog.SERVICE,
        "GET " + StoryTarget.API_PATH + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "http",
        ACTOR,
        StoryAccessLog.SERVICE,
        "GET " + StoryTarget.describePath(REPO_ID) + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "http",
        ACTOR,
        StoryAccessLog.SERVICE,
        "GET " + StoryTarget.browseTreePath(REPO_ID, StoryOrigin.BRANCH) + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "http",
        ACTOR,
        StoryAccessLog.SERVICE,
        "GET " + StoryTarget.browseFilePath(REPO_ID, StoryOrigin.BRANCH, FILE) + " -> 200");

    // Four requests, four edges, and no fifth: this plane is one page's worth of reads and the
    // count is the contract the SPA is written against.
    ReportAssertions.assertEdgeCount(CATEGORY, SLUG, 4);
    // The negative claim this story exists to make: the browser plane reached this service and
    // this service reached NOBODY. A lookup edge appearing here would mean the browse routes had
    // started resolving names server-side, which is a different design than the one shipped.
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR));
    ReportAssertions.assertNoEdgesTo(CATEGORY, SLUG, MockProjects.SERVICE_NAME);
    ReportAssertions.assertNotLeaked(CATEGORY, SLUG, token);
  }
}
