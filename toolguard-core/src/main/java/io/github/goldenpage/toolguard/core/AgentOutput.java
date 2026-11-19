package io.github.goldenpage.toolguard.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AgentOutput(
    String conclusion,
    List<String> citedEvidenceIds,
    Map<String, Object> attributes
) {
    public AgentOutput {
        Objects.requireNonNull(conclusion, "conclusion");
        citedEvidenceIds = citedEvidenceIds == null ? List.of() : List.copyOf(citedEvidenceIds);
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
