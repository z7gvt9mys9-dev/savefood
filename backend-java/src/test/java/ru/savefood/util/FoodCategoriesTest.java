package ru.savefood.util;
import static org.assertj.core.api.Assertions.assertThat;
import ru.savefood.util.FoodCategories.Signal;
import org.junit.jupiter.api.Test;
class FoodCategoriesTest {
    @Test
    void everyPublishableCategoryParticipatesInScoring() {
        for (String category : FoodCategories.NAMES) {
            assertThat(FoodCategories.KEYWORDS.get(category))
                .as("категория %s должна иметь ключевые слова", category)
                .isNotNull().isNotEmpty();
        }
        assertThat(FoodCategories.preferenceSignal("Овощи/Фрукты", "люблю овощи")).isEqualTo(Signal.MATCH);
        assertThat(FoodCategories.preferenceSignal("Готовая еда", "нельзя консервы")).isEqualTo(Signal.CONFLICT);
    }
    @Test
    void positiveMentionIsAMatch() {
        assertThat(FoodCategories.preferenceSignal("Молочные продукты", "люблю творог и кефир"))
            .isEqualTo(Signal.MATCH);
        assertThat(FoodCategories.preferenceSignal("Выпечка", "хлеб подойдёт")).isEqualTo(Signal.MATCH);
    }
    @Test
    void restrictionWordMakesItAConflict() {
        assertThat(FoodCategories.preferenceSignal("Молочные продукты", "аллергия на молоко"))
            .isEqualTo(Signal.CONFLICT);
        assertThat(FoodCategories.preferenceSignal("Выпечка", "без глютена")).isEqualTo(Signal.CONFLICT);
        assertThat(FoodCategories.preferenceSignal("Молочные продукты", "не ем сыр"))
            .isEqualTo(Signal.CONFLICT);
    }
    /** A restriction anywhere must beat any number of positive mentions. */
    @Test
    void restrictionBeatsPositiveMentionInAnotherClause() {
        assertThat(FoodCategories.preferenceSignal("Молочные продукты",
            "люблю молочное, но аллергия на сыр")).isEqualTo(Signal.CONFLICT);
    }
    @Test
    void unrelatedPreferencesAreNeutral() {
        assertThat(FoodCategories.preferenceSignal("Выпечка", "аллергия на орехи")).isEqualTo(Signal.NEUTRAL);
        assertThat(FoodCategories.preferenceSignal("Выпечка", "")).isEqualTo(Signal.NEUTRAL);
        assertThat(FoodCategories.preferenceSignal("Выпечка", null)).isEqualTo(Signal.NEUTRAL);
        assertThat(FoodCategories.preferenceSignal(null, "хлеб")).isEqualTo(Signal.NEUTRAL);
        assertThat(FoodCategories.preferenceSignal("Неизвестная категория", "хлеб")).isEqualTo(Signal.NEUTRAL);
    }
    @Test
    void matchingIsCaseInsensitive() {
        assertThat(FoodCategories.preferenceSignal("Выпечка", "ХЛЕБ")).isEqualTo(Signal.MATCH);
        assertThat(FoodCategories.preferenceSignal("Выпечка", "БЕЗ ГЛЮТЕНА")).isEqualTo(Signal.CONFLICT);
    }
    @Test
    void onlyCataloguedCategoriesAreAccepted() {
        assertThat(FoodCategories.isKnown("Выпечка")).isTrue();
        assertThat(FoodCategories.isKnown(" Выпечка ")).isTrue();
        assertThat(FoodCategories.isKnown("Мясо")).isFalse();
        assertThat(FoodCategories.isKnown(null)).isFalse();
    }
    /** The UI offers exactly these four; ESG and scoring must agree. */
    @Test
    void catalogueIsTheFourPublishableCategories() {
        assertThat(FoodCategories.NAMES)
            .containsExactly("Выпечка", "Овощи/Фрукты", "Готовая еда", "Молочные продукты");
        assertThat(FoodCategories.KEYWORDS.keySet())
            .containsExactlyInAnyOrderElementsOf(FoodCategories.NAMES);
    }
}
