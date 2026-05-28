package dev.newsflash.i18n;

import java.util.List;

public final class LanguageRegistry {
    public static final List<String> SUPPORTED_LANGUAGES = List.of(
        "ja",
        "en",
        "zh_CN",
        "zh_TW",
        "ko",
        "de",
        "fr",
        "es",
        "pt_BR",
        "ru"
    );

    private LanguageRegistry() {
    }

    public static String normalize(String language) {
        if (language == null) {
            return "ja";
        }
        for (String supportedLanguage : SUPPORTED_LANGUAGES) {
            if (supportedLanguage.equalsIgnoreCase(language)) {
                return supportedLanguage;
            }
        }
        return "ja";
    }

    public static boolean supported(String language) {
        if (language == null) {
            return false;
        }
        return SUPPORTED_LANGUAGES.stream().anyMatch(supportedLanguage -> supportedLanguage.equalsIgnoreCase(language));
    }
}
