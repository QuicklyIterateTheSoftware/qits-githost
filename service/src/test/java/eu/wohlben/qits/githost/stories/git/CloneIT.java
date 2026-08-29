package eu.wohlben.qits.githost.stories.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * The first thing anybody does with a git host: {@code git clone}, with the real client, over the
 * real wire, against the packaged process.
 *
 * <p>Everything else in this repository proves the clone from the inside — {@code GitHostTest}
 * shells the same binary against the in-process routes, and its subject is git semantics. This
 * story's subject is the <b>public clone url</b>: {@code /git/:projectId/:repoName}, the spelling a
 * developer types, a workspace remote holds and a committed relative submodule url resolves to. It
 * is the one shape that cannot be served without asking qits-projects what the name means, so the
 * diagram this story emits has two hops in it and neither is narrated.
 *
 * <p>Browserless: {@link Interactions} and {@link Commands}, no {@code Flow}, so no Chromium is
 * launched and the report is the transcript plus the diagram.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@EnabledIf("eu.wohlben.qits.githost.stories.support.StoryTools#gitPresent")
public class CloneIT {

  static final String CATEGORY = "git";
  static final String SLUG = "a-developer-clones-a-repository";

  /** How the diagram names the initiator of everything this story sends. */
  static final String ACTOR = "a developer";

  /** The repository, as the world names it — {@code qits/hello-world}. */
  static final String REPO_NAME = "hello-world";

  /** …and as this host stores it. The story never spells this; the fixture does. */
  static final String REPO_ID = MockProjects.repositoryId(REPO_NAME);

  static final String FILE = "README.md";

  static final String CONTENT = "# hello-world\n\nThe repository the clone story clones.\n";

  /**
   * The bearer, kept static because {@code @AfterAll} asserts it never reached the bundle and a
   * static method cannot read an instance field. Minted fresh per run by the mock idp.
   */
  private static String token;

  /** What the origin's {@code main} points at, as the fixture left it — the far end of the proof. */
  private static String seededSha;

  @TestHTTPResource("/")
  URL root;

  /**
   * Wires both halves of the diagram, once. The near side is the launched process' own access log
   * (nothing in this JVM is on the socket {@code git} uses); the far side is the resolver mock's
   * recording. Both are cumulative sources, drained by the framework at story end.
   */
  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryAccessLog.install();
    MockProjects.installSource();
  }

  /**
   * The state the story starts from, put there the way the platform puts it there: qits-projects'
   * client provisions a repository by storage id and pushes the first commit into it. None of this
   * is a story step and none of it is a story's traffic — see {@link StoryOrigin}.
   */
  @BeforeEach
  void theProjectHasARepository() {
    token = StoryOrigin.bearer();
    seededSha =
        StoryOrigin.provisionSeeded(
            new StoryTarget(root), REPO_ID, token, Map.of(FILE, CONTENT), "the first commit");
  }

  @UserStory(value = "A developer clones a repository", category = "git")
  @UserStoryDescription(
      """
      The git host as a developer meets it: one `git clone` against the public url —
      `<host>/git/<project>/<repository>` — carrying a platform bearer, and a working copy that
      holds the repository's files. Nothing about the storage is visible from here: this host keys
      repositories by an id qits-projects mints and holds no name for any of them, so serving this
      url means asking qits-projects what the name means, on every request and with nothing cached.
      The clone's own HEAD is compared against the commit the origin was given, so what is proved
      is the repository's answer rather than the tool's exit code.
      """)
  void aDeveloperClonesARepository(Interactions story, Commands commands) throws IOException {
    StoryTarget target = new StoryTarget(root);
    // The bearer is masked out of the whole bundle — transcripts, step lines, the sidecar and the
    // network labels alike. It rides on `git -c http.extraHeader`, so it is on every command line
    // below and would otherwise be in the published report four times over.
    commands.redact(token);
    // Who the access log's lines belong to from here on. Read when the framework drains at story
    // end, so it is set before the first request rather than beside the assertion it shapes.
    StoryAccessLog.actor(ACTOR);

    // git wants a writable HOME (it reads ~/.gitconfig and may write nothing at all, but a build
    // agent's HOME is frequently unwritable) and must never take a credential prompt: an
    // unauthorized clone has to fail as a refusal, not hang waiting for a username.
    commands.env("HOME", commands.workDir().toAbsolutePath().toString());
    commands.env("GIT_TERMINAL_PROMPT", "0");

    story
        .note("qits-projects has provisioned the repository and pushed its first commit")
        .as("origin-provisioned");

    // The whole story, in one command. The url is an ARGUMENT rather than part of the template:
    // the launched process listens on a random port, and a port in the fingerprint would move this
    // story's definition hash on every run.
    commands
        .run(
            "{} -c {} clone {} {}",
            StoryTools.git(),
            StoryOrigin.authConfig(token),
            target.cloneUrl(REPO_NAME),
            "work")
        .as("repository-cloned");

    // End (a): the working copy holds the file, with the bytes the origin was given.
    Path clone = commands.workDir().resolve("work");
    assertEquals(
        CONTENT, Files.readString(clone.resolve(FILE)), "the clone must hold the origin's " + FILE);

    // End (b): and it is at the origin's commit, not at some commit of its own. `rev-parse` in the
    // clone rather than `ls-remote` against the host, deliberately — the question here is what the
    // developer ended up with.
    commands.in("work");
    commands.run("{} rev-parse HEAD", StoryTools.git()).as("clone-head-read");
    assertEquals(
        seededSha,
        commands.lastOutput().strip(),
        "the clone's HEAD must be the commit the origin advertised");

    story
        .note("the working copy is at the origin's commit and holds the file it was given")
        .as("clone-verified");

    // The access log is written off the request thread, so the pack response can be back before
    // its line is on disk. Waiting for it here is what keeps the edge in THIS story's diagram.
    StoryAccessLog.awaitLogged("POST " + StoryTarget.clonePath(REPO_NAME) + "/git-upload-pack");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!StoryTools.gitPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "origin-provisioned");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repository-cloned");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "clone-head-read");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "clone-verified");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "clone", 0);

    // The network, observed rather than claimed: the first two edges are lines the launched
    // process wrote about what `git` sent it, the third is the resolver mock's own recording of
    // what this service then asked IT.
    //
    // `git` rather than `http` for the protocol shapes: a clone is a negotiated multi-request
    // exchange, and the transport carrying it says nothing about what it means.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "git",
        ACTOR,
        StoryAccessLog.SERVICE,
        "GET " + StoryTarget.clonePath(REPO_NAME) + "/info/refs?service=git-upload-pack -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "git",
        ACTOR,
        StoryAccessLog.SERVICE,
        "POST " + StoryTarget.clonePath(REPO_NAME) + "/git-upload-pack -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        "http",
        StoryAccessLog.SERVICE,
        MockProjects.SERVICE_NAME,
        MockProjects.lookupLabel(REPO_NAME, 200));

    // NO assertEdgeCount, and the reason is worth keeping: how many requests a clone is belongs to
    // the CLIENT. Protocol v2 splits the negotiation into an ls-refs and a fetch — two POSTs that
    // dedupe to one edge here — while v0 sends one, and a git old enough to fall back to the dumb
    // protocol would send several more. What this repository owes a reader is WHO talked to WHOM,
    // and that is a closed set: the developer reached this service, this service reached
    // qits-projects, and nothing else initiated anything.
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, SLUG, List.of(ACTOR, StoryAccessLog.SERVICE));

    // The bundle as bytes: the bearer is on four command lines and must be in none of them.
    ReportAssertions.assertNotLeaked(CATEGORY, SLUG, token);
  }
}
