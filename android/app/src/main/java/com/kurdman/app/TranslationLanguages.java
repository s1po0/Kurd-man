package com.kurdman.app;
/** Existing source and target catalogue, shared with saved-job validation. */
final class TranslationLanguages {
    static final String[] SOURCES = {
            "en", "ko", "ja", "zh-CN", "zh-TW"
    };
    static final String[] TARGETS = {
            "ckb", "ku", "ar", "en", "fa", "tr", "ru", "de", "fr", "es",
            "pt", "it", "nl", "pl", "uk", "id", "ms", "hi", "ur", "bn",
            "ta", "te", "mr", "gu", "kn", "ml", "pa", "th", "vi", "ko",
            "ja", "zh-CN", "zh-TW", "el", "sv", "da", "fi", "no", "cs", "hu",
            "ro", "bg", "sr", "hr", "uz", "kk", "az", "he", "ps", "sw",
            "am", "my", "km", "ne", "si", "ka", "hy", "lt", "lv", "et",
            "sk", "sl", "ga", "cy", "mt", "is", "mk", "sq", "be", "ca",
            "gl", "eu", "af", "tl"
    };
    static final String[] LABELS = {
            "کوردی سۆرانی", "Kurdî (Kurmanji)", "العربية", "English",
            "فارسی", "Türkçe", "Русский", "Deutsch", "Français", "Español",
            "Português", "Italiano", "Nederlands", "Polski", "Українська",
            "Bahasa Indonesia", "Bahasa Melayu", "हिन्दी", "اردو", "বাংলা",
            "தமிழ்", "తెలుగు", "मराठी", "ગુજરાતી", "ಕನ್ನಡ", "മലയാളം",
            "ਪੰਜਾਬੀ", "ไทย", "Tiếng Việt", "한국어",
            "日本語", "中文（简体）", "中文（繁體）", "Ελληνικά", "Svenska",
            "Dansk", "Suomi", "Norsk", "Čeština", "Magyar",
            "Română", "Български", "Српски", "Hrvatski", "O'zbek",
            "Қазақша", "Azərbaycan", "עברית", "پښتو", "Kiswahili",
            "አማርኛ", "မြန်မာ", "ខ្មែរ", "नेपाली", "සිංහල",
            "ქართული", "Հայերեն", "Lietuvių", "Latviešu", "Eesti",
            "Slovenčina", "Slovenščina", "Gaeilge", "Cymraeg", "Malti",
            "Íslenska", "Македонски", "Shqip", "Беларуская", "Català",
            "Galego", "Euskara", "Afrikaans", "Filipino"
    };
    static boolean source(String value) { return java.util.Arrays.asList(SOURCES).contains(value); }
    static boolean target(String value) { return java.util.Arrays.asList(TARGETS).contains(value); }
}
