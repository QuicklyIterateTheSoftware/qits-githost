package eu.wohlben.qits.githost.stories.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The other half of the wire: {@code git push}, and the proof that the far end moved.
 *
 * <p>Receive-pack is the <b>only writer this storage has</b>. A repository here has no directory
 * and no file anywhere — its packs, pack indexes and reftables are content-addressed blobs, and the
 * pack list is rows — so a commit exists on this host if and only if a push carried it in. That is
 * what makes the second half of this story the interesting half: the ref the origin now advertises,
 * and the file the origin now serves, are both read back over the wire rather than inferred from
 * git's exit code.
 *
 * <p>Two planes in one diagram, which is why the {@code kind} vocabulary is used honestly rather
 * than uniformly: the clone and the push are {@code git} (a negotiated protocol exchange), and the
 * content read that follows is {@code http} (one GET, one file, no negotiation at all).
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@EnabledIf("eu.wohlben.qits.githost.stories.support.StoryTools#gitAndCurlPresent")
public class PushIT {

  static final String CATEGORY = "git";
  static final String SLUG = "a-push-lands-new-history";

  static final String ACTOR = "a developer";

  /** {@code qits/release-notes} — as the world names it. */
  static final String REPO_NAME = "release-notes";

  static final String REPO_ID = MockProjects.repositoryId(REPO_NAME);

  /** What the fixture seeds, so the push is an UPDATE of an existing branch rather than its birth. */
  static final String SEEDED_FILE = "README.md";

  static final String SEEDED_CONTENT = "# release-notes\n";

  /** What the story writes and pushes — the new history. */
  static final String PUSHED_FILE = "CHANGELOG.md";

  static final String PUSHED_CONTENT = "# Changelog\n\n- the git host serves what was pushed to it\n";

  private static String token;

  /** Where the origin's {@code main} stood before the push; the assertion that it MOVED needs it. */
  private static String seededSha;

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
    seededSha =
        StoryOrigin.provisionSeeded(
            new StoryTarget(root),
            REPO_ID,
            token,
            Map.of(SEEDED_FILE, SEEDED_CONTENT),
            "the first commit");
  }

  @UserStory(value = "A push lands new history", category = "git")
  @UserStoryDescription(
      """
      A developer clones, commits and pushes — and the repository on the other side is a different
      repository afterwards. Receive-pack is the only writer this storage has: nothing here is a
      directory, so a commit exists on this host only because a push carried it in. The proof is
      therefore taken from the host and not from the tool: `ls-remote` is asked what
      `refs/heads/main` points at now, and the content route is asked for the pushed file, so both
      the ref and the bytes are answers the origin gave rather than claims the client made.
      """)
  void aPushLandsNewHistory(Interactions story, Commands commands) {
    StoryTarget target = new StoryTarget(root);
    commands.redact(token);
    StoryAccessLog.actor(ACTOR);

    commands.env("HOME", commands.workDir().toAbsolutePath().toString());
    commands.env("GIT_TERMINAL_PROMPT", "0");

    story.note("the repository already holds a first commit on main").as("origin-provisioned");

    commands
        .run(
            "{} -c {} clone {} {}",
            StoryTools.git(),
            StoryOrigin.authConfig(token),
            target.cloneUrl(REPO_NAME),
            "work")
        .as("repository-cloned");
    commands.in("work");

    // The file is written through the facade rather than with Files.writeString, so it lands in
    // the report as an artifact under its own step: what was pushed is part of the evidence.
    commands.file(PUSHED_FILE, PUSHED_CONTENT).as("change-written");
    commands.run("{} add {}", StoryTools.git(), PUSHED_FILE);
    commands
        .run(
            "{} -c user.email={} -c user.name={} commit -q -m {}",
            StoryTools.git(),
            "developer@qits.local",
            "a developer",
            "note what this release changes")
        .as("change-committed");

    commands.run("{} rev-parse HEAD", StoryTools.git());
    String pushedSha = commands.lastOutput().strip();
    assertNotEquals(seededSha, pushedSha, "the commit under test must be new history");

    commands
        .run("{} -c {} push origin {}", StoryTools.git(), StoryOrigin.authConfig(token), StoryOrigin.BRANCH)
        .as("history-pushed");

    // End (a): what the ORIGIN advertises now. `ls-remote` asks the host the same question a
    // fetching client asks, which is the only question a DFS-backed repository can be asked —
    // there is no bare directory anywhere for a `rev-parse` to open.
    commands
        .run(
            "{} -c {} ls-remote {} {}",
            StoryTools.git(),
            StoryOrigin.authConfig(token),
            target.cloneUrl(REPO_NAME),
            "refs/heads/" + StoryOrigin.BRANCH)
        .as("origin-ref-read");
    assertTrue(
        commands.lastOutput().contains(pushedSha),
        () -> "the origin must advertise the pushed commit, not " + commands.lastOutput());

    // End (b): and the bytes are there too. The content route serves one file at one revision over
    // plain HTTP — no clone, no negotiation — which is how qits-ci reads a pipeline definition.
    commands
        .run(
            "{} -sS -f -H {} {}",
            StoryTools.curl(),
            "Authorization: Bearer " + token,
            target.blobUrl(REPO_NAME, StoryOrigin.BRANCH, PUSHED_FILE))
        .as("pushed-file-served");
    assertEquals(
        PUSHED_CONTENT.strip(),
        commands.lastOutput().strip(),
        "the host must serve the file the push carried");

    story
        .note("the origin's main names the pushed commit and the host serves the file it carried")
        .as("push-verified");

    StoryAccessLog.awaitLogged("GET " + StoryTarget.blobPath(REPO_NAME, StoryOrigin.BRANCH, PUSHED_FILE));
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!StoryTools.gitAndCurlPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "origin-provisioned");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repository-cloned");
    ReportAssertions.assertWroteFile(CATEGORY, SLUG, PUSHED_FILE);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "change-committed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "history-pushed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "origin-ref-read");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "pushed-file-served");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "push-verified");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "push origin", 0);

    String repo = StoryTarget.clonePath(REPO_NAME);
    // The clone half — the ref advertisement `ls-remote` asks for again afterwards, so the two
    // dedupe into one edge, which is right: they are the same request to the same route.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "git",
        ACTOR,
        StoryAccessLog.SERVICE,
        "GET " + repo + "/info/refs?service=git-upload-pack -> 200");
    ReportAssertions.assertEdge(
        CATEGORY, SLUG, "git", ACTOR, StoryAccessLog.SERVICE, "POST " + repo + "/git-upload-pack -> 200");
    // The push half. The advertisement a push asks for is a DIFFERENT one — `?service=` is what
    // says so, and it is the reason this tap records %U (path AND query) rather than %R.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "git",
        ACTOR,
        StoryAccessLog.SERVICE,
        "GET " + repo + "/info/refs?service=git-receive-pack -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "git",
        ACTOR,
        StoryAccessLog.SERVICE,
        "POST " + repo + "/git-receive-pack -> 200");
    // The content read, which is a plain request and is recorded as one.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "http",
        ACTOR,
        StoryAccessLog.SERVICE,
        "GET " + StoryTarget.blobPath(REPO_NAME, StoryOrigin.BRANCH, PUSHED_FILE) + " -> 200");
    // Every one of those five requests made this service ask qits-projects what the name means —
    // nothing is cached, deliberately, so a rename can never serve a stale id. Same question, same
    // answer, so the diagram draws one arrow.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "http",
        StoryAccessLog.SERVICE,
        MockProjects.SERVICE_NAME,
        MockProjects.lookupLabel(REPO_NAME, 200));

    // No count: the request count of a clone and a push is the client's (protocol v2 splits the
    // fetch in two; a chunked push may re-send). The actor set is this repository's promise.
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR, StoryAccessLog.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY, SLUG, token);
  }
}
