package eu.wohlben.qits.githost.persistence;

import java.io.Serializable;
import java.util.Objects;

/** Composite key of {@link GitPackFile}: one pack has at most one file per extension. */
public class GitPackFileId implements Serializable {

  public String repositoryId;
  public String packName;
  public String extension;

  public GitPackFileId() {}

  public GitPackFileId(String repositoryId, String packName, String extension) {
    this.repositoryId = repositoryId;
    this.packName = packName;
    this.extension = extension;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof GitPackFileId id
        && Objects.equals(repositoryId, id.repositoryId)
        && Objects.equals(packName, id.packName)
        && Objects.equals(extension, id.extension);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repositoryId, packName, extension);
  }
}
