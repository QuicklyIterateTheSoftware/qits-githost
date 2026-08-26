package eu.wohlben.qits.githost.loc;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One language's line counts at one commit, split the way the code page draws them. Travels inside
 * {@code RepositoryBrowseResource}'s untyped {@code Response}, so the reflection registration is
 * load-bearing in the native image — the same rule every record on that resource documents.
 */
@RegisterForReflection
public record LanguageLoc(String language, long mainLines, long testLines) {}
