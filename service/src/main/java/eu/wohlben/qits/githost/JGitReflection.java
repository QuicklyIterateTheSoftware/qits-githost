package eu.wohlben.qits.githost;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.CommitConfig;
import org.eclipse.jgit.lib.CoreConfig;
import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.SubmoduleConfig;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.transport.HttpConfig;
import org.eclipse.jgit.transport.PushConfig;
import org.eclipse.jgit.transport.TransferConfig;
import org.eclipse.jgit.util.sha1.SHA1;

/**
 * Native-image reflection registrations for JGit. JGit is not a Quarkus extension, so no build step
 * knows anything about it; everything it does reflectively has to be declared here or the compiled
 * binary fails at runtime while the JVM suite stays green.
 *
 * <p>All of it is one mechanism. {@code Config.getEnum(...)} — how JGit reads every enum-valued key
 * out of a repository's {@code config} — recovers the constants with {@code
 * value.getClass().getMethod("values").invoke(null)}. Unregistered, that throws {@code
 * NoSuchMethodException} the first time a repository is opened, {@code FileRepositoryBuilder.build}
 * fails, and {@code GitHostRoutes.open} returns null: <b>every</b> git request answers 404 and
 * looks exactly like an unknown repo id. {@code core.trustStat} is the one that lands first.
 *
 * <p>The list is the enum types JGit passes to {@code getEnum} anywhere in the jar, not the subset
 * this service's paths happen to reach today, because reading one more config key is not a change
 * anybody would think to re-verify natively. It was derived from the 7.3 jar's bytecode; a JGit
 * upgrade that adds a config enum needs it extended, and {@code GitHostRoutes.open} logs the cause
 * at debug so the symptom is diagnosable rather than a bare 404.
 *
 * <p>An annotation holder only — never instantiated, and it deliberately holds no other native
 * configuration: the JGit statics that cannot be frozen into the image heap are named in {@code
 * application.properties}, where the {@code --initialize-at-run-time} flags live.
 */
@RegisterForReflection(
    targets = {
      MergeCommand.FastForwardMode.Merge.class,
      DiffAlgorithm.SupportedAlgorithm.class,
      DirCache.DirCacheVersion.class,
      BranchConfig.BranchRebaseMode.class,
      CommitConfig.CleanupMode.class,
      CoreConfig.AutoCRLF.class,
      CoreConfig.CheckStat.class,
      CoreConfig.EOL.class,
      CoreConfig.HideDotFiles.class,
      CoreConfig.LogRefUpdates.class,
      CoreConfig.SymLinks.class,
      CoreConfig.TrustStat.class,
      GpgConfig.GpgFormat.class,
      SubmoduleConfig.FetchRecurseSubmodulesMode.class,
      SubmoduleWalk.IgnoreSubmoduleMode.class,
      HttpConfig.HttpRedirectMode.class,
      PushConfig.PushDefault.class,
      PushConfig.PushRecurseSubmodulesMode.class,
      TransferConfig.FsckMode.class,
      SHA1.Sha1Implementation.class
    })
final class JGitReflection {

  private JGitReflection() {}
}
