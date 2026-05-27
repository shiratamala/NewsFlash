package dev.newsflash.config;

import java.util.List;

public record FilterConfig(
    boolean enabled,
    List<String> keywords
) {
}
