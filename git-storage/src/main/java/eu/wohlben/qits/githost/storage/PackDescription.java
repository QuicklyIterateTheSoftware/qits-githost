package eu.wohlben.qits.githost.storage;

import java.util.List;
import java.util.Objects;

/**
 * One pack of one repository, as the catalog stores it: a name, the numbers JGit needs to order and
 * plan around it, and the blobs that hold its files.
 *
 * <p>This is the module's own value type rather than JGit's {@code DfsPackDescription}, and that is
 * the point of it. Everything in this record is a string, a number or a list of them, so a catalog
 * implementation can write columns without ever naming a type from {@code
 * org.eclipse.jgit.internal.*} — which is what confines a JGit upgrade to this module.
 *
 * <p>A pack is identified by {@link #packName} within a repository; a catalog may treat {@code
 * (repositoryId, packName)} as its primary key. Names are unique for all time (a UUID, see {@link
 * QitsDfsObjDatabase}), so a name is never reused and a row is never updated in place.
 *
 * @param packName the pack's name, with no extension and no dot in it
 * @param source where the pack came from, JGit's {@code PackSource} by name — {@code RECEIVE},
 *     {@code INSERT}, {@code GC}, {@code COMPACT}, {@code UNREACHABLE_GARBAGE}, ... A string for the
 *     reason {@link PackFile#extension} is one; an unknown value is read back as {@code UNREACHABLE_GARBAGE}
 *     rather than failing the whole repository.
 * @param lastModified when the pack was written, in epoch milliseconds. Load-bearing: it is a
 *     sorting key for both object lookup and the reftable stack, so it has to survive a restart.
 * @param objectCount objects in the pack, {@code 0} if unknown
 * @param deltaCount delta-compressed objects in the pack, {@code 0} if unknown
 * @param minUpdateIndex reftables only: the lowest update index this table covers
 * @param maxUpdateIndex reftables only: the highest update index this table covers. The primary
 *     ordering key of the reftable stack — refs read wrong if it is lost.
 * @param indexVersion the pack index format version, {@code 0} for JGit's default
 * @param files the pack's files, at least one, at most one per extension
 */
public record PackDescription(
    String packName,
    String source,
    long lastModified,
    long objectCount,
    long deltaCount,
    long minUpdateIndex,
    long maxUpdateIndex,
    int indexVersion,
    List<PackFile> files) {

  public PackDescription {
    Objects.requireNonNull(packName, "packName");
    Objects.requireNonNull(source, "source");
    files = List.copyOf(Objects.requireNonNull(files, "files"));
  }
}
