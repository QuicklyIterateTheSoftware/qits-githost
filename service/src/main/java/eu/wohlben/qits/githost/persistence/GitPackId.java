package eu.wohlben.qits.githost.persistence;

import java.io.Serializable;
import java.util.Objects;

/** Composite key of {@link GitPack}: a repository and a pack name, unique for all time. */
public class GitPackId implements Serializable {

  public String repositoryId;
  public String packName;

  public GitPackId() {}

  public GitPackId(String repositoryId, String packName) {
    this.repositoryId = repositoryId;
    this.packName = packName;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof GitPackId id
        && Objects.equals(repositoryId, id.repositoryId)
        && Objects.equals(packName, id.packName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repositoryId, packName);
  }
}
