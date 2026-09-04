package ru.savefood.volunteer;
import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
class RouteRevertPickupTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static boolean pickedUp(String json) {
        return RouteRevertService.pickedUp(json, MAPPER);
    }
    @Test
    void shopPointDoneMeansFoodLeftTheShop() {
        assertThat(pickedUp("""
            [{"kind":"shop","lat":43.2,"lon":76.9,"done":true},
             {"kind":"ticket","ticket_id":7,"lat":43.3,"lon":76.8}]
            """)).isTrue();
    }
    @Test
    void shopPointNotDoneMeansFoodIsStillOnTheShelf() {
        assertThat(pickedUp("""
            [{"kind":"shop","lat":43.2,"lon":76.9},
             {"kind":"ticket","ticket_id":7,"lat":43.3,"lon":76.8}]
            """)).isFalse();
        assertThat(pickedUp("""
            [{"kind":"shop","lat":43.2,"lon":76.9,"done":false}]
            """)).isFalse();
    }
    /** Delivered stops do not imply pickup — only the shop point does. */
    @Test
    void completedTicketStopsAloneDoNotCount() {
        assertThat(pickedUp("""
            [{"kind":"ticket","ticket_id":7,"done":true}]
            """)).isFalse();
    }
    /** Anything unreadable falls to the conservative branch: revert the lot. */
    @Test
    void malformedOrEmptyPointsAreTreatedAsNotPickedUp() {
        assertThat(pickedUp(null)).isFalse();
        assertThat(pickedUp("")).isFalse();
        assertThat(pickedUp("   ")).isFalse();
        assertThat(pickedUp("{not json")).isFalse();
        assertThat(pickedUp("[]")).isFalse();
        assertThat(pickedUp("{\"kind\":\"shop\",\"done\":true}")).isFalse();
    }
}
