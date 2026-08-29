package eu.wohlben.qits.githost.stories.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Reading one file out of a repository <b>without cloning it</b> — the flow qits-ci runs before
 * every pipeline and the workspace daemon runs on every file open.
 *
 * <p>A clone is the wrong tool for a question about one file: it negotiates, it transfers a pack,
 * and it leaves a working copy behind. So this host serves the same repository over two plain
 * routes on the public scheme — a directory listing at {@code …/tree/:rev} and the bytes at
 * {@code …/blob/:rev/<path>} — and this story is what they are for. Both are ordinary GETs and are
 * recorded as {@code http}: there is no protocol here, which is the whole point.
 *
 * <p><b>This is the one story whose request count is the SERVICE's promise rather than a client's,
 * so it is pinned.</b> Two reads is two reads: nothing negotiates, nothing retries, and the number
 * of requests a caller must make to answer "what is in this directory, and what does that file
 * say" is a property of the routes rather than of curl. Hence {@code assertEdgeCount} here and
 * nowhere in the clone or push stories.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@EnabledIf("eu.wohlben.qits.githost.stories.support.StoryTools#curlPresent")
public class ReadFileIT {

  static final String CATEGORY = "git";
  static final String SLUG = "a-pipeline-reads-a-file-without-cloning";

  static final String ACTOR = "a build pipeline";

  /** {@code qits/pipeline-config} — a repository a pipeline reads and never clones. */
  static final String REPO_NAME = "pipeline-config";

  static final String REPO_ID = MockProjects.repositoryId(REPO_NAME);

  /** The directory the listing is asked for, and the file inside it the pipeline actually wants. */
  static final String DIRECTORY = "ci";

  static final String FILE = DIRECTORY + "/pipeline.yml";

  static final String FILE_CONTENT =
      """
      steps:
        - name: verify
          run: ./mvnw -B verify
      """;

  private static String token;

  @TestHTTPResource("/")
  URL root;

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryAccessLog.install();
    MockProjects.installSource();
  }

  @BeforeEach
  void theProjectHasARepository() {
    token = StoryOrigin.bearer();
    Map<String, String> seed = new LinkedHashMap<>();
    seed.put("README.md", "# pipeline-config\n");
    seed.put(FILE, FILE_CONTENT);
    StoryOrigin.provisionSeeded(new StoryTarget(root), REPO_ID, token, seed, "the first commit");
  }

  @UserStory(value = "A pipeline reads a file without cloning", category = "git")
  @UserStoryDescription(
      """
      A build pipeline needs one file — its own definition — out of a repository it has no reason
      to clone. The git host answers that in two plain GETs on the same public url a developer
      clones from: the tree at a revision says what is there, and the blob route hands back the
      file's bytes verbatim, with the commit it was read at in a response header. No pack is
      negotiated, no working copy is created, and no credential beyond the platform bearer the
      pipeline already holds is involved.
      """)
  void aPipelineReadsAFileWithoutCloning(Interactions story, Commands commands) {
    StoryTarget target = new StoryTarget(root);
    commands.redact(token);
    StoryAccessLog.actor(ACTOR);

    story
        .note("the pipeline knows the repository by its public name and holds a platform bearer")
        .as("pipeline-ready");

    // What is in the directory. The listing is one level deep — a client that wants a subtree asks
    // for the subtree — so this is the answer to a question, not a walk of the whole repository.
    commands
        .run(
            "{} -sS -f -H {} {}",
            StoryTools.curl(),
            "Authorization: Bearer " + token,
            target.treeUrl(REPO_NAME, StoryOrigin.BRANCH) + "/" + DIRECTORY)
        .as("directory-listed");
    assertTrue(
        commands.lastOutput().contains("\"name\":\"pipeline.yml\""),
        () -> "the listing must name the file: " + commands.lastOutput());

    // …and the file itself, byte for byte. `-f` so a 4xx is a non-zero exit and the story fails on
    // the refusal rather than on the assertion below.
    commands
        .run(
            "{} -sS -f -H {} {}",
            StoryTools.curl(),
            "Authorization: Bearer " + token,
            target.blobUrl(REPO_NAME, StoryOrigin.BRANCH, FILE))
        .as("file-read");
    assertEquals(
        FILE_CONTENT.strip(),
        commands.lastOutput().strip(),
        "the blob route must serve the committed bytes");

    story
        .note("the pipeline holds its definition, and nothing was cloned to get it")
        .as("file-verified");

    StoryAccessLog.awaitLogged(
        "GET " + StoryTarget.blobPath(REPO_NAME, StoryOrigin.BRANCH, FILE));
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!StoryTools.curlPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "pipeline-ready");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "directory-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "file-read");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "file-verified");
    // The transcript of the blob read, from the emitted artifact rather than the inline excerpt.
    // "/blob/" is what tells the two curl commands apart — the listing's url carries "/tree/".
    ReportAssertions.assertCommandOutputContains(CATEGORY, SLUG, "/blob/", "steps:");

    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "http",
        ACTOR,
        StoryAccessLog.SERVICE,
        "GET " + StoryTarget.treePath(REPO_NAME, StoryOrigin.BRANCH) + "/" + DIRECTORY + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "http",
        ACTOR,
        StoryAccessLog.SERVICE,
        "GET " + StoryTarget.blobPath(REPO_NAME, StoryOrigin.BRANCH, FILE) + " -> 200");
    // Both reads made this service ask qits-projects what the name means. Nothing is cached, so it
    // asked twice — and both asks are the same question with the same answer, so the diagram draws
    // one arrow.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "http",
        StoryAccessLog.SERVICE,
        MockProjects.SERVICE_NAME,
        MockProjects.lookupLabel(REPO_NAME, 200));

    // THE COUNT IS THE SERVICE'S PROMISE HERE, unlike everywhere else in this category: two reads,
    // one lookup shape, and nothing else on the wire. A third edge appearing means either a route
    // started answering differently or this service grew a dependency, and both are worth failing
    // a build over.
    ReportAssertions.assertEdgeCount(CATEGORY, SLUG, 3);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR, StoryAccessLog.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY, SLUG, token);
  }
}
