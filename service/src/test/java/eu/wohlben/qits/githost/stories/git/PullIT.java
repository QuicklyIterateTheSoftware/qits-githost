package eu.wohlben.qits.githost.stories.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * What a git host is actually <i>for</i>: two people, two working copies, and one of them ending up
 * with the other's work.
 *
 * <p>This is the only story in the catalogue with <b>two initiators</b>, and it is the reason
 * {@link StoryAccessLog} stamps each log line as it harvests rather than reading one actor at drain
 * time. The developer clones, the teammate pushes, the developer pulls — and the diagram says
 * exactly that: two arrows into this service from two different people, plus the one this service
 * makes to qits-projects to know what the name means. A tap that could only name one initiator per
 * story would draw the teammate's push as the developer's, which is the opposite of what happened.
 *
 * <p>The hand-overs are the delicate part. The access log is written off the request thread, so a
 * line can still be in flight when the story switches actors — and a line harvested after the
 * switch is stamped with the wrong person. Every hand-over here is therefore preceded by an
 * {@link StoryAccessLog#awaitLogged} on the last request the outgoing actor made.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@EnabledIf("eu.wohlben.qits.githost.stories.support.StoryTools#gitPresent")
public class PullIT {

  static final String CATEGORY = "git";
  static final String SLUG = "a-pull-fetches-what-a-teammate-pushed";

  static final String DEVELOPER = "a developer";
  static final String TEAMMATE = "a teammate";

  /** {@code qits/shared-notes} — one repository, two working copies. */
  static final String REPO_NAME = "shared-notes";

  static final String REPO_ID = MockProjects.repositoryId(REPO_NAME);

  static final String SEEDED_FILE = "NOTES.md";

  static final String SEEDED_CONTENT = "# shared notes\n";

  /** The teammate's contribution — the thing the developer does not have until the pull. */
  static final String TEAMMATE_FILE = "MEETING.md";

  static final String TEAMMATE_CONTENT = "# meeting\n\n- agreed to ship the git stories\n";

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
    StoryOrigin.provisionSeeded(
        new StoryTarget(root),
        REPO_ID,
        token,
        Map.of(SEEDED_FILE, SEEDED_CONTENT),
        "the first commit");
  }

  @UserStory(value = "A pull fetches what a teammate pushed", category = "git")
  @UserStoryDescription(
      """
      Two working copies of one repository, which is the whole reason a git host exists. The
      developer clones and, at that moment, has everything there is. The teammate clones, writes,
      commits and pushes. The developer — who has not touched anything — pulls, and now holds a
      file that was never on their machine, at the commit the teammate created. The story checks
      the absence first: the file must NOT be in the working copy before the pull, or the pull
      would be proving nothing.
      """)
  void aPullFetchesWhatATeammatePushed(Interactions story, Commands commands) throws IOException {
    StoryTarget target = new StoryTarget(root);
    commands.redact(token);
    commands.env("HOME", commands.workDir().toAbsolutePath().toString());
    commands.env("GIT_TERMINAL_PROMPT", "0");

    // ABSOLUTE, both of them. The work directory is a relative path (target/userstories-work/…),
    // and `git -C` below runs with its working directory inside the teammate's copy — a relative
    // spelling would be resolved against that and fail with "cannot change to".
    Path developerCopy = commands.workDir().resolve("developer").toAbsolutePath();
    Path teammateCopy = commands.workDir().resolve("teammate").toAbsolutePath();

    // --- the developer, at the state of the world before anything happened --------------------
    StoryAccessLog.actor(DEVELOPER);
    commands
        .run(
            "{} -c {} clone {} {}",
            StoryTools.git(),
            StoryOrigin.authConfig(token),
            target.cloneUrl(REPO_NAME),
            "developer")
        .as("developer-cloned");
    assertFalse(
        Files.exists(developerCopy.resolve(TEAMMATE_FILE)),
        "the developer must not already hold the file the pull is about");
    story
        .note("the developer's working copy holds the repository as it stands, and nothing more")
        .as("developer-up-to-date");

    // --- hand-over. The last line the developer produced must be on disk before the switch, or
    // it would be harvested under the teammate's name.
    StoryAccessLog.awaitLogged("POST " + StoryTarget.clonePath(REPO_NAME) + "/git-upload-pack");
    StoryAccessLog.actor(TEAMMATE);

    commands
        .run(
            "{} -c {} clone {} {}",
            StoryTools.git(),
            StoryOrigin.authConfig(token),
            target.cloneUrl(REPO_NAME),
            "teammate")
        .as("teammate-cloned");
    commands.in("teammate");
    commands.file(TEAMMATE_FILE, TEAMMATE_CONTENT).as("teammate-wrote");
    commands.run("{} add {}", StoryTools.git(), TEAMMATE_FILE);
    commands.run(
        "{} -c user.email={} -c user.name={} commit -q -m {}",
        StoryTools.git(),
        "teammate@qits.local",
        "a teammate",
        "write up the meeting");
    commands.run("{} rev-parse HEAD", StoryTools.git());
    String teammateSha = commands.lastOutput().strip();
    commands
        .run(
            "{} -c {} push origin {}",
            StoryTools.git(),
            StoryOrigin.authConfig(token),
            StoryOrigin.BRANCH)
        .as("teammate-pushed");

    // --- hand-over back.
    StoryAccessLog.awaitLogged("POST " + StoryTarget.clonePath(REPO_NAME) + "/git-receive-pack");
    StoryAccessLog.actor(DEVELOPER);

    // `-C` with an absolute path rather than another `in(…)`: the work directory's cursor is
    // inside the teammate's copy now, and a story never climbs out of a directory it changed into.
    commands
        .run(
            "{} -C {} -c {} pull --ff-only origin {}",
            StoryTools.git(),
            developerCopy,
            StoryOrigin.authConfig(token),
            StoryOrigin.BRANCH)
        .as("developer-pulled");

    // Both ends of the proof, in the developer's copy: the file that was never here, and the
    // commit the teammate made.
    assertTrue(
        Files.exists(developerCopy.resolve(TEAMMATE_FILE)),
        "the pull must have brought the teammate's file into the developer's copy");
    assertEquals(
        TEAMMATE_CONTENT,
        Files.readString(developerCopy.resolve(TEAMMATE_FILE)),
        "…with the bytes the teammate committed");
    commands.run("{} -C {} rev-parse HEAD", StoryTools.git(), developerCopy).as("developer-head-read");
    assertEquals(
        teammateSha,
        commands.lastOutput().strip(),
        "the developer's HEAD must be the commit the teammate pushed");
    assertTrue(Files.isDirectory(teammateCopy), "the teammate's copy is a second, separate clone");

    story
        .note("the developer now holds the teammate's commit and the file that came with it")
        .as("pull-verified");

    StoryAccessLog.awaitLogged("POST " + StoryTarget.clonePath(REPO_NAME) + "/git-upload-pack");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!StoryTools.gitPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "developer-cloned");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "developer-up-to-date");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "teammate-cloned");
    ReportAssertions.assertWroteFile(CATEGORY, SLUG, TEAMMATE_FILE);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "teammate-pushed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "developer-pulled");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "pull-verified");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "pull --ff-only", 0);

    String repo = StoryTarget.clonePath(REPO_NAME);
    String advertise = "GET " + repo + "/info/refs?service=git-upload-pack -> 200";
    String fetch = "POST " + repo + "/git-upload-pack -> 200";

    // The developer read twice — the clone and the pull ask the same two questions, so they dedupe
    // into these two edges.
    ReportAssertions.assertEdge(CATEGORY, SLUG, "git", DEVELOPER, StoryAccessLog.SERVICE, advertise);
    ReportAssertions.assertEdge(CATEGORY, SLUG, "git", DEVELOPER, StoryAccessLog.SERVICE, fetch);
    // The teammate read the same way and then wrote, which is two more routes and the same actor.
    ReportAssertions.assertEdge(CATEGORY, SLUG, "git", TEAMMATE, StoryAccessLog.SERVICE, advertise);
    ReportAssertions.assertEdge(CATEGORY, SLUG, "git", TEAMMATE, StoryAccessLog.SERVICE, fetch);
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "git",
        TEAMMATE,
        StoryAccessLog.SERVICE,
        "GET " + repo + "/info/refs?service=git-receive-pack -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "git",
        TEAMMATE,
        StoryAccessLog.SERVICE,
        "POST " + repo + "/git-receive-pack -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "http",
        StoryAccessLog.SERVICE,
        MockProjects.SERVICE_NAME,
        MockProjects.lookupLabel(REPO_NAME, 200));

    // The actor set IS the story: exactly two people and this service, and nobody else reached
    // anything. The request counts behind them belong to the clients.
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, SLUG, List.of(DEVELOPER, TEAMMATE, StoryAccessLog.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY, SLUG, token);
  }
}
