package eu.wohlben.qits.githost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The shipped {@link RepositoryNameResolver}: it asks qits-projects, which owns the alias table, over
 * one GET.
 *
 * <pre>
 *   GET &lt;qits.projects.name-resolver-url&gt;/{projectId}/repositories/by-name/{repoName}
 *   200 -&gt; {"repositoryId": "&lt;id&gt;"}
 *   404 -&gt; unknown project, or no repository under that name
 * </pre>
 *
 * <p><b>Absent config is a supported configuration and answers nothing.</b> With {@code
 * qits.projects.name-resolver-url} unset this returns empty without dialling anything, so the
 * name-addressed scheme 404s exactly as it did before this class existed — the same 404 {@link
 * GitHostRoutes#openByName} produces when no resolver bean is present at all.
 *
 * <p><b>It never throws.</b> {@code GitHostRoutes} has no exception clause on this port, so anything
 * escaping here becomes a 500 where the contract promises a 404. A timeout, a refused connection, a
 * non-200 or a body this cannot read are all logged at WARN with the url and answered empty. That is
 * the opposite of the GC pin ports ({@code CdHttpDeploymentPins}, {@code CiHttpDaemonPins}), which
 * throw on purpose because a run that cannot read a pin must delete nothing.
 *
 * <p><b>Nothing is cached</b>, for the reason the pin ports state: a rename must not serve a stale
 * id. A cached alias would keep clones flowing to the repository a name used to mean, and this repo
 * has no caching idiom for an outbound lookup to borrow.
 *
 * <p>{@link DefaultBean} so a consuming application — or the test suite, which ships {@code
 * FakeRepositoryNameResolver} — can supply its own implementation without making {@code
 * Instance<RepositoryNameResolver>} ambiguous. In production this is the only bean.
 */
@ApplicationScoped
@DefaultBean
public class HttpRepositoryNameResolver implements RepositoryNameResolver {

  private static final Logger LOG = Logger.getLogger(HttpRepositoryNameResolver.class);

  /** Bounded like every other outbound call here: connect, then the request, and no retries. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  /**
   * An <b>instance</b> field, not a static one. {@link PostReceiveNotifier}'s own client states the
   * native-image rule in full: a static one is built by the class initialiser, which under GraalVM
   * runs at image-build time, and the image is then refused over an {@code HttpClientFacade} in its
   * heap. {@code @ApplicationScoped} still means one client per process.
   */
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  /**
   * Where qits-projects answers the lookup, up to but not including {@code /{projectId}}. The PATH
   * below it is qits-projects' own, exactly as with {@code qits.projects.intake-url}; only the host
   * part is a deployment decision (`http://qits-projects:&lt;port&gt;/projects/api/projects`).
   *
   * <p>An {@code Optional}, never a {@code String} with an empty default: SmallRye reads a
   * configured-empty value as <em>absent</em>, so a {@code String} injection of an unset or blank key
   * kills the packaged binary at boot with "Failed to load config value of type java.lang.String" —
   * the trap {@code MirrorUpstream.endpointOverride} documents and {@code PackagedProcessIT} caught.
   */
  @ConfigProperty(name = "qits.projects.name-resolver-url")
  Optional<String> resolverUrl;

  @Inject ObjectMapper objectMapper;

  @Override
  public Optional<String> resolveRepositoryId(String projectId, String name) {
    String base = resolverUrl.map(String::trim).filter(u -> !u.isEmpty()).orElse(null);
    if (base == null || projectId == null || name == null) {
      return Optional.empty();
    }
    // GitHostRoutes strips the suffix already; belt and braces, because a `.git` that reached the
    // url would look up a name qits-projects never registered.
    String bare = name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    String url =
        base + "/" + segment(projectId) + "/repositories/by-name/" + segment(bare);
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() == 404) {
        // The documented "no such name" answer, not a fault: nothing to log.
        return Optional.empty();
      }
      if (response.statusCode() != 200) {
        LOG.warnf("name lookup %s answered %d", url, response.statusCode());
        return Optional.empty();
      }
      // A JsonNode, never a bound DTO — the wire rule this repo keeps so nothing needs
      // @RegisterForReflection.
      JsonNode body = objectMapper.readTree(response.body());
      JsonNode id = body == null ? null : body.get("repositoryId");
      if (id == null || !id.isTextual() || id.asText().isBlank()) {
        LOG.warnf("name lookup %s answered a body with no repositoryId", url);
        return Optional.empty();
      }
      return Optional.of(id.asText());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      LOG.warnf("name lookup %s interrupted", url);
      return Optional.empty();
    } catch (Exception e) {
      LOG.warnf("name lookup %s failed: %s", url, e.toString());
      return Optional.empty();
    }
  }

  /** One path segment. {@code URLEncoder} is form encoding, so its {@code +} needs undoing. */
  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
