package eu.wohlben.qits.githost.persistence;

import java.io.Serializable;
import java.util.Objects;

/** Composite key of {@link GitRepositoryLoc}: a repository and the commit its summary describes. */
public class GitRepositoryLocId implements Serializable {

  public String repositoryId;
  public String commitSha;

  public GitRepositoryLocId() {}

  public GitRepositoryLocId(String repositoryId, String commitSha) {
    this.repositoryId = repositoryId;
    this.commitSha = commitSha;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof GitRepositoryLocId id
        && Objects.equals(repositoryId, id.repositoryId)
        && Objects.equals(commitSha, id.commitSha);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repositoryId, commitSha);
  }
}
