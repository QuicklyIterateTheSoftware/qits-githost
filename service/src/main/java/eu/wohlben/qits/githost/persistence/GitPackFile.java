package eu.wohlben.qits.githost.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One file of one pack, and the blob that holds it: {@code pack}, {@code idx}, {@code reftable}, and
 * whatever a later JGit adds.
 *
 * <p>{@link #extension} is a string rather than a JGit {@code PackExt}, on purpose: a stored row
 * must not carry another library's enum, or an upgrade that renames one silently changes what the
 * rows already written mean.
 *
 * <p>{@link #blobId} is an ordinary content address in the same blob store every other byte of this
 * service lives in. Deleting this row frees none of them — see {@link GitPack} on why that is the
 * recorded posture rather than an omission.
 */
@Entity
@Table(name = "git_pack_file")
@IdClass(GitPackFileId.class)
public class GitPackFile extends PanacheEntityBase {

  @Id
  @Column(name = "repository_id")
  public String repositoryId;

  @Id
  @Column(name = "pack_name", length = 128)
  public String packName;

  @Id
  @Column(length = 32)
  public String extension;

  @Column(name = "blob_id", nullable = false, length = 64)
  public String blobId;

  /** {@code size} spelled {@code file_size}: the shorter name is a keyword in too many dialects. */
  @Column(name = "file_size", nullable = false)
  public long size;

  @Column(name = "block_size", nullable = false)
  public int blockSize;
}
