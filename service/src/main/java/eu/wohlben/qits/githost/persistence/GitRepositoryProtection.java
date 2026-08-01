package eu.wohlben.qits.githost.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One repository's answer to "is the default branch protected here" — the override that used to be
 * {@code [qits] protectDefaultBranch} in the bare's own config.
 *
 * <p>It became a row because a DFS-backed repository has no config file: {@code
 * DfsRepository.getConfig()} is an in-memory {@code DfsConfig} whose load and save are no-ops, so
 * the old read would have answered the platform default for every repository with no symptom at all.
 *
 * <p><b>It is the override source for both backends</b>, not only the new one. Two mechanisms for
 * one question would eventually disagree, and the disagreement would show up as a push refused on
 * one storage engine and accepted on the other.
 *
 * <p>No row means no override: the platform-wide {@code
 * qits.repositories.git.protect-default-branch} decides, exactly as an absent config line did.
 */
@Entity
@Table(name = "git_repository_protection")
public class GitRepositoryProtection extends PanacheEntityBase {

  @Id
  @Column(name = "repository_id")
  public String repositoryId;

  @Column(name = "protect_default_branch", nullable = false)
  public boolean protectDefaultBranch;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
