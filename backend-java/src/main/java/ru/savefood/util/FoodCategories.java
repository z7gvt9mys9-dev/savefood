package ru.savefood.util;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
public final class FoodCategories {
    private FoodCategories() {
    }
    public static final String BAKERY = "Выпечка";
    public static final String PRODUCE = "Овощи/Фрукты";
    public static final String PREPARED = "Готовая еда";
    public static final String DAIRY = "Молочные продукты";
    /** Every category a lot may carry. Order is the UI order. */
    public static final List<String> NAMES = List.of(BAKERY, PRODUCE, PREPARED, DAIRY);
    public static final Map<String, List<String>> KEYWORDS;
    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(BAKERY, List.of("хлеб", "выпечк", "булк", "батон", "лаваш", "глютен", "мука", "пшениц", "злак"));
        m.put(PRODUCE, List.of("овощ", "фрукт", "яблок", "картофел", "капуст", "морков"));
        m.put(PREPARED, List.of("готовая еда", "готовой еды", "обед", "консерв", "крупа", "каш"));
        m.put(DAIRY, List.of("молок", "молоч", "лактоз", "сыр", "творог", "кефир", "йогурт", "сметан"));
        KEYWORDS = Map.copyOf(m);
    }
    /** Words that flip a mention of a category into an explicit restriction. */
    public static final List<String> RESTRICTION_WORDS =
        List.of("аллергия", "нельзя", "не ем", "без ", "непереносимость", "не могу", "запрет");
    private static final Pattern CLAUSE_SPLIT = Pattern.compile("[.,;\\n]+");
    /** What a recipient's preferences say about this category. */
    public enum Signal {
        /** Category not mentioned — no effect on ranking. */
        NEUTRAL,
        /** Mentioned approvingly — the recipient wants this. */
        MATCH,
        /** Mentioned together with a restriction word — must not receive it. */
        CONFLICT
    }
    public static boolean isKnown(String category) {
        return category != null && NAMES.contains(category.strip());
    }
    public static Signal preferenceSignal(String category, String preferences) {
        if (preferences == null || preferences.isBlank()) {
            return Signal.NEUTRAL;
        }
        List<String> keywords = KEYWORDS.get(category == null ? "" : category.strip());
        if (keywords == null) {
            return Signal.NEUTRAL;
        }
        boolean matched = false;
        for (String raw : CLAUSE_SPLIT.split(preferences.toLowerCase())) {
            String clause = raw.strip();
            if (clause.isEmpty() || keywords.stream().noneMatch(clause::contains)) {
                continue;
            }
            if (RESTRICTION_WORDS.stream().anyMatch(clause::contains)) {
                return Signal.CONFLICT;
            }
            matched = true;
        }
        return matched ? Signal.MATCH : Signal.NEUTRAL;
    }
}
