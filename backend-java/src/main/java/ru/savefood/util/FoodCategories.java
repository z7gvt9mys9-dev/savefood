package ru.savefood.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one catalogue of food categories, and the one place that reads a
 * recipient's free-text preferences against it.
 *
 * <p>Before this existed the project had three disagreeing vocabularies: the shop
 * UI offered four categories, the ESG CO₂ table keyed on those same four, but the
 * routing score matched a different five ({@code мясо}/{@code рыба}/{@code орехи}
 * instead of {@code Овощи/Фрукты}/{@code Готовая еда}) via a substring test. The
 * result was that half the keyword table was unreachable and, worse, half of the
 * categories a shop can actually publish had their dietary restrictions silently
 * ignored during selection — a recipient who cannot eat prepared food was not
 * protected, while one avoiding gluten was.
 *
 * <p>{@link #NAMES} is authoritative: the shop UI offers exactly these, the API
 * validates against them, ESG weights them and the selection scorer reads their
 * keywords.
 */
public final class FoodCategories {

    private FoodCategories() {
    }

    public static final String BAKERY = "Выпечка";
    public static final String PRODUCE = "Овощи/Фрукты";
    public static final String PREPARED = "Готовая еда";
    public static final String DAIRY = "Молочные продукты";

    /** Every category a lot may carry. Order is the UI order. */
    public static final List<String> NAMES = List.of(BAKERY, PRODUCE, PREPARED, DAIRY);

    /**
     * Words that put a category into a recipient's preference clause. Matched
     * against lowercased text, so all stems are lowercase and deliberately short
     * (Russian inflection: "молок", "молоч", … all start the same).
     */
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

    /**
     * Read {@code preferences} clause by clause (split on {@code . , ; newline}),
     * looking only at clauses that mention {@code category}. A restriction word
     * anywhere in such a clause wins over any number of positive mentions —
     * "люблю молочное, но аллергия на сыр" must not be read as a preference.
     */
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
