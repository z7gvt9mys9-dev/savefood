package ru.savefood.billing;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PlansTest {

    @Test
    void basicHasNoOcrOrEsg() {
        Plans.Plan basic = Plans.get("basic");
        assertThat(basic.ocr()).isFalse();
        assertThat(basic.esg()).isFalse();
        assertThat(basic.monthlyLotLimit()).isEqualTo(20);
    }

    @Test
    void proHasOcrAndEsg() {
        Plans.Plan pro = Plans.get("pro");
        assertThat(pro.ocr()).isTrue();
        assertThat(pro.esg()).isTrue();
        assertThat(pro.monthlyLotLimit()).isNull();
    }

    @Test
    void enterpriseHasAll() {
        Plans.Plan ent = Plans.get("enterprise");
        assertThat(ent.ocr()).isTrue();
        assertThat(ent.esg()).isTrue();
        assertThat(ent.api()).isTrue();
    }

    @Test
    void unknownPlanFallsBackToBasic() {
        Plans.Plan p = Plans.get("nonexistent");
        assertThat(p).isEqualTo(Plans.get("basic"));
    }

    @Test
    void hasFeatureBasicOcr() {
        assertThat(Plans.hasFeature("basic", "ocr")).isFalse();
        assertThat(Plans.hasFeature("basic", "esg")).isFalse();
        assertThat(Plans.hasFeature("basic", "api")).isFalse();
    }

    @Test
    void hasFeatureProAndEnterprise() {
        assertThat(Plans.hasFeature("pro", "ocr")).isTrue();
        assertThat(Plans.hasFeature("pro", "esg")).isTrue();
        assertThat(Plans.hasFeature("enterprise", "api")).isTrue();
    }

    @Test
    void unknownFeatureReturnsFalse() {
        assertThat(Plans.hasFeature("enterprise", "unknown_feature")).isFalse();
    }
}
