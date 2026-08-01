package eu.wohlben.qits.githost.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One pack of one repository — the row half of {@code PackDescription}, and the only mutable state a
 * DFS-backed git repository has.
 *
 * <p><b>The platform does not garbage collect git, deliberately.</b> A pack's blobs are immutable
 * and nothing frees them — the blob store's one delete is package-private to its sweep, which does
 * not run — so a repack does not reclaim: it duplicates. The new pack is written, the packs it
 * replaced lose their rows here, and their bytes stay forever. Measured on the
 * platform's largest real repository, one {@code DfsGarbageCollector} run took it from 7.8 MB to 15
 * MB. The accepted cost instead is roughly three blobs and three rows per push, about 75 blobs per
 * active repository per year. Deleting a row here frees nothing.
 *
 * <p>{@link #packName} is a UUID and is never reused. {@code (repositoryId, packName)} identifies a
 * pack for all time, a row is never updated in place, and a name collision would not raise an error
 * — JGit compares descriptions by name, so it would serve the wrong bytes.
 *
 * <p>{@link #lastModified}, {@link #minUpdateIndex} and {@link #maxUpdateIndex} must round-trip
 * exactly. They are sorting keys — for object lookup and for the reftable stack — so losing one is
 * invisible in a single run and reads refs wrong after a restart.
 */
@Entity
@Table(name = "git_pack")
@IdClass(GitPackId.class)
public class GitPack extends PanacheEntityBase {

  @Id
  @Column(name = "repository_id")
  public String repositoryId;

  @Id
  @Column(name = "pack_name", length = 128)
  public String packName;

  /**
   * JGit's {@code PackSource} by name. A string rather than an enum so a JGit version that adds one
   * needs no migration; an unrecognised value is read back as {@code UNREACHABLE_GARBAGE}.
   */
  @Column(nullable = false, length = 64)
  public String source;

  @Column(name = "last_modified", nullable = false)
  public long lastModified;

  @Column(name = "object_count", nullable = false)
  public long objectCount;

  @Column(name = "delta_count", nullable = false)
  public long deltaCount;

  @Column(name = "min_update_index", nullable = false)
  public long minUpdateIndex;

  @Column(name = "max_update_index", nullable = false)
  public long maxUpdateIndex;

  @Column(name = "index_version", nullable = false)
  public int indexVersion;
}
