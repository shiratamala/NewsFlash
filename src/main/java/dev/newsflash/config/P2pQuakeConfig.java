package dev.newsflash.config;

public record P2pQuakeConfig(
    boolean enabled,
    String websocketUrl,
    int reconnectDelaySeconds,
    boolean earthquakeEnabled,
    int minScale,
    boolean includeUnknownScale,
    int seenHistoryLimit
) {
}
