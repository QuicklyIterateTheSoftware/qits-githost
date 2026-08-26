package eu.wohlben.qits.githost.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One repository's lines-of-code summary at one commit — an immutable memo of {@code
 * RepositoryLocScanner}, which is a pure function of the commit's tree. A row is written once and
 * never updated; two writers racing on the same key hold identical payloads by construction.
 *
 * <p>{@link #payload} is the JSON the browse endpoint answers, verbatim, so a memo hit costs one
 * primary-key read and no re-serialization. No row means "not computed yet", never "no code": the
 * reader rescans on a miss, so losing rows — including to the writer's own prune — costs a rescan
 * and nothing else.
 */
@Entity
@Table(name = "git_repository_loc")
@IdClass(GitRepositoryLocId.class)
public class GitRepositoryLoc extends PanacheEntityBase {

  @Id
  @Column(name = "repository_id")
  public String repositoryId;

  @Id
  @Column(name = "commit_sha", length = 64)
  public String commitSha;

  @Column(nullable = false)
  public String payload;

  @Column(name = "computed_at", nullable = false)
  public Instant computedAt;
}
