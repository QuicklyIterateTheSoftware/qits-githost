/**
 * A git repository whose bytes live in a content-addressed blob store instead of on a filesystem.
 *
 * <p>Four classes and two ports. {@link eu.wohlben.qits.githost.storage.QitsDfsRepository} is a JGit
 * {@code DfsRepository}: {@code UploadPack} and {@code ReceivePack} take a {@code Repository}, so
 * the smart-HTTP endpoints that serve it need no change at all — only whatever opens the repository
 * does. Its packs, its pack indexes and its <b>refs</b> (reftable, see {@link
 * eu.wohlben.qits.githost.storage.QitsDfsReftableDatabase}) are all blobs in one store, listed by a
 * catalog keyed on a repository id.
 *
 * <p><b>This package extends {@code org.eclipse.jgit.internal.storage.dfs}, and "internal" is
 * JGit's word, not ours.</b> That package carries no API stability promise between releases: its
 * abstract methods may be added to, renamed or re-signed in any version. This module is therefore
 * the blast radius of a JGit upgrade — see {@code README.md} in the module root for what to check
 * when {@code jgit.version} moves, and why the ports below hand out no type from it.
 *
 * <p>Nothing here is a CDI bean and nothing here reads configuration. The module is built to run in
 * a plain JUnit test with no Quarkus, no database and no docker.
 */
package eu.wohlben.qits.githost.storage;
