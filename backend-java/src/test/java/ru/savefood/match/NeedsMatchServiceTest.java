package ru.savefood.match;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class NeedsMatchServiceTest {
    @Test
    void positiveMentionMatches() {
        assertThat(NeedsMatchService.matchesPreferences("Молочные продукты",
            "У меня 3 детей, нужна молочка и каши")).isTrue();
        assertThat(NeedsMatchService.matchesPreferences("Выпечка",
            "хлеб нужен всегда")).isTrue();
        assertThat(NeedsMatchService.matchesPreferences("Готовая еда",
            "нужны каши и крупа")).isTrue();
    }
    @Test
    void restrictionBeatsPositiveMention() {
        assertThat(NeedsMatchService.matchesPreferences("Молочные продукты",
            "аллергия на молоко")).isFalse();
        assertThat(NeedsMatchService.matchesPreferences("Выпечка",
            "не ем хлеб")).isFalse();
        assertThat(NeedsMatchService.matchesPreferences("Молочные продукты",
            "без молочных продуктов, пожалуйста")).isFalse();
    }
    @Test
    void restrictionInOtherClauseDoesNotBlock() {
        assertThat(NeedsMatchService.matchesPreferences("Молочные продукты",
            "люблю молоко, нет аллергий на орехи")).isTrue();
    }
    @Test
    void unrelatedPreferencesDoNotMatch() {
        assertThat(NeedsMatchService.matchesPreferences("Выпечка",
            "нужны овощи и фрукты")).isFalse();
    }
    @Test
    void emptyInputs() {
        assertThat(NeedsMatchService.matchesPreferences(null, "хлеб")).isFalse();
        assertThat(NeedsMatchService.matchesPreferences("Выпечка", null)).isFalse();
        assertThat(NeedsMatchService.matchesPreferences("Выпечка", "")).isFalse();
        assertThat(NeedsMatchService.matchesPreferences("Неизвестная категория", "хлеб")).isFalse();
    }
    @Test
    void haversineKnownDistance() {
        double d = NeedsMatchService.haversine(55.7, 37.6, 56.7, 37.6);
        assertThat(d).isBetween(110_000.0, 112_000.0);
    }
    @Test
    void haversineZero() {
        assertThat(NeedsMatchService.haversine(43.25, 76.95, 43.25, 76.95)).isEqualTo(0.0);
    }
}
