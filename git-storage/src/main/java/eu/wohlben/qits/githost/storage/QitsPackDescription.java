package eu.wohlben.qits.githost.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;

/**
 * A JGit pack description that also remembers which blob holds each of its files.
 *
 * <p>The pairing has to live on the description because that is the only thing JGit carries from
 * {@code newPack} through {@code writeFile} to {@code commitPack}, and back out of {@code listPacks}
 * into {@code openFile}. It is the whole translation between JGit's world (a pack has files named
 * {@code <name>.<ext>}) and the store's (a blob has a content address and no name).
 *
 * <p>Package-private on purpose: it names three JGit internal types, so it stays inside the module
 * that is allowed to. {@link PackDescription} is the same thing with the JGit taken out, and that is
 * what crosses the ports.
 */
final class QitsPackDescription extends DfsPackDescription {

  /** Written by {@code writeFile} while JGit is still streaming, read by {@code openFile}. */
  private final Map<PackExt, String> blobIds = new ConcurrentHashMap<>();

  /** Kept because {@code DfsPackDescription} has no getter for it, only {@code getFileName(ext)}. */
  private final String packName;

  QitsPackDescription(DfsRepositoryDescription repository, String name, PackSource source) {
    super(repository, name, source);
    this.packName = name;
  }

  String blobId(PackExt ext) {
    return blobIds.get(ext);
  }

  void blobId(PackExt ext, String blobId) {
    blobIds.put(ext, blobId);
  }

  /** The catalog's view of this pack. Called once the pack's files are all written. */
  PackDescription toRecord() {
    List<PackFile> files = new ArrayList<>(blobIds.size());
    for (Map.Entry<PackExt, String> entry : blobIds.entrySet()) {
      PackExt ext = entry.getKey();
      files.add(
          new PackFile(
              ext.getExtension(), entry.getValue(), getFileSize(ext), getBlockSize(ext)));
    }
    return new PackDescription(
        packName,
        getPackSource().name(),
        getLastModified(),
        getObjectCount(),
        getDeltaCount(),
        getMinUpdateIndex(),
        getMaxUpdateIndex(),
        getIndexVersion(),
        files);
  }

  /**
   * Rebuilds a description from a catalog row.
   *
   * <p>Two lookups here are deliberately forgiving, because the alternative is that a repository
   * stops opening after a JGit downgrade: a {@code source} this JGit does not know is read as {@code
   * UNREACHABLE_GARBAGE}, which is searched last but still searched, and a file extension it does
   * not know is dropped rather than thrown.
   */
  static QitsPackDescription fromRecord(DfsRepositoryDescription repository, PackDescription row) {
    QitsPackDescription desc =
        new QitsPackDescription(repository, row.packName(), packSource(row.source()));
    desc.setLastModified(row.lastModified());
    desc.setObjectCount(row.objectCount());
    desc.setDeltaCount(row.deltaCount());
    desc.setMinUpdateIndex(row.minUpdateIndex());
    desc.setMaxUpdateIndex(row.maxUpdateIndex());
    desc.setIndexVersion(row.indexVersion());
    for (PackFile file : row.files()) {
      PackExt ext = packExt(file.extension());
      if (ext == null) {
        continue;
      }
      desc.addFileExt(ext);
      desc.setFileSize(ext, file.size());
      desc.setBlockSize(ext, file.blockSize());
      desc.blobId(ext, file.blobId());
    }
    return desc;
  }

  private static PackSource packSource(String name) {
    for (PackSource source : PackSource.values()) {
      if (source.name().equals(name)) {
        return source;
      }
    }
    return PackSource.UNREACHABLE_GARBAGE;
  }

  private static PackExt packExt(String extension) {
    for (PackExt ext : PackExt.values()) {
      if (ext.getExtension().equals(extension)) {
        return ext;
      }
    }
    return null;
  }
}
