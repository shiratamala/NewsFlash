package dev.newsflash.config;

import java.util.List;

public record P2pQuakeConfig(
    boolean enabled,
    String websocketUrl,
    int reconnectDelaySeconds,
    boolean earthquakeEnabled,
    int minScale,
    List<String> targetPrefectures,
    boolean includeUnknownScale,
    int seenHistoryLimit
) {
}
