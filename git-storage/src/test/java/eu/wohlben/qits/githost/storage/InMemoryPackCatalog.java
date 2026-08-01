package eu.wohlben.qits.githost.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link PackCatalog} in a map, standing in for the rows a database would hold.
 *
 * <p>It is shared between repository instances by the test, deliberately: a repository object holds
 * no state, so opening a new one over the same catalog is what a restart looks like from here. Half
 * this suite depends on that being true.
 */
final class InMemoryPackCatalog implements PackCatalog {

  private final Map<String, LinkedHashMap<String, PackDescription>> repositories =
      new ConcurrentHashMap<>();

  private final List<String> rolledBack = new ArrayList<>();

  @Override
  public synchronized List<PackDescription> list(String repositoryId) {
    LinkedHashMap<String, PackDescription> packs = repositories.get(repositoryId);
    return packs == null ? List.of() : List.copyOf(packs.values());
  }

  @Override
  public synchronized void commit(
      String repositoryId, Collection<PackDescription> add, Collection<PackDescription> remove) {
    LinkedHashMap<String, PackDescription> packs =
        repositories.computeIfAbsent(repositoryId, id -> new LinkedHashMap<>());
    for (PackDescription pack : remove) {
      packs.remove(pack.packName());
    }
    for (PackDescription pack : add) {
      packs.put(pack.packName(), pack);
    }
  }

  @Override
  public synchronized void rollback(String repositoryId, Collection<PackDescription> packs) {
    for (PackDescription pack : packs) {
      rolledBack.add(pack.packName());
    }
  }

  /** How many packs a repository has, which is also how many rows the deployment would hold. */
  synchronized int packCount(String repositoryId) {
    return list(repositoryId).size();
  }

  synchronized List<String> rolledBack() {
    return List.copyOf(rolledBack);
  }
}
