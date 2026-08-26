package eu.wohlben.qits.githost.loc;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

/**
 * A commit's whole lines-of-code summary: one {@link LanguageLoc} per language the map names,
 * sorted largest total first. Both the browse endpoint's answer and the stored memo, byte for byte.
 *
 * <p><b>Deliberately spelled without the {@code rev} the caller asked under.</b> Two askers reach
 * one commit under different names — a branch, a sha, the push that computed it ahead of both — and
 * a rev echoed here would make their stored payloads differ over a field that means nothing to the
 * summary. Everything in this record is a pure function of {@code commitSha}, which is what lets
 * the memo store the serialized answer once and serve it to every spelling.
 *
 * <p>Reflection registration is load-bearing: this travels inside an untyped {@code Response} and
 * the service ships as a native image — the rule {@code RepositoryBrowseResource} documents.
 */
@RegisterForReflection
public record LocResponse(String commitSha, List<LanguageLoc> languages) {}
