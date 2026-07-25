package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import ru.savefood.needy.NeedyRepository;
import ru.savefood.needy.NeedyService;
import ru.savefood.security.PasswordService;
import ru.savefood.volunteer.RouteRevertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reservation lifecycle end to end, against real Postgres.
 *
 * <p>Every case here is a hole a previous audit found (or the fix for one), and
 * none of them are reachable from the pure-logic unit suite: they turn on
 * guarded updates, partial unique indexes and transaction rollback.
 */
class ReservationLifecycleIT extends PostgresIT {

    private NeedyService needyService;
    private RouteRevertService revert;

    @BeforeEach
    void wire() {
        needyService = new NeedyService(jdbc, new NeedyRepository(jdbc), new PasswordService());
        revert = new RouteRevertService(jdbc);
    }

    // ── reservation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("создание заявки атомарно резервирует единицу лота")
    void ticketCreationReservesAUnit() {
        int shop = insertShop("Магазин", 43.238, 76.889);
        int lot = insertLot(shop, 5.0, "Выпечка");
        int needy = insertNeedy("Получатель");

        int ticket = needyService.createTicket(needy, "хлеб", "адрес", 43.24, 76.90,
            null, lot, null, null, null, false);

        assertThat(lotQuantity(lot)).isEqualTo(4.0);
        assertThat(status("tickets", ticket)).isEqualTo("open");
    }

    @Test
    @DisplayName("последняя единица разбирается один раз, второму отказ")
    void theLastUnitCannotBeReservedTwice() {
        int shop = insertShop("Магазин", 43.238, 76.889);
        int lot = insertLot(shop, 1.0, "Выпечка");
        int first = insertNeedy("Первый");
        int second = insertNeedy("Второй");

        needyService.createTicket(first, "хлеб", "адрес", 43.24, 76.90, null, lot,
            null, null, null, false);

        assertThatThrownBy(() -> needyService.createTicket(second, "хлеб", "адрес", 43.24, 76.90,
            null, lot, null, null, null, false))
            .isInstanceOf(NeedyService.TicketCreateException.class)
            .hasMessageContaining("lot_unavailable");
        assertThat(lotQuantity(lot)).isEqualTo(0.0);
    }

    /** Backed by uq_tickets_one_active_per_needy, not by the pre-check alone. */
    @Test
    @DisplayName("вторая активная заявка одного получателя отклоняется")
    void oneActiveTicketPerRecipient() {
        int shop = insertShop("Магазин", 43.238, 76.889);
        int lot = insertLot(shop, 5.0, "Выпечка");
        int needy = insertNeedy("Получатель");

        needyService.createTicket(needy, "хлеб", "адрес", 43.24, 76.90, null, lot,
            null, null, null, false);

        assertThatThrownBy(() -> needyService.createTicket(needy, "ещё", "адрес", 43.24, 76.90,
            null, lot, null, null, null, false))
            .isInstanceOf(NeedyService.TicketCreateException.class)
            .hasMessageContaining("active_ticket_exists");
    }

    /** A rejected reservation must not leak the unit it briefly decremented. */
    @Test
    @DisplayName("откат транзакции возвращает зарезервированную единицу")
    void rollbackReturnsTheReservedUnit() {
        int shop = insertShop("Магазин", 43.238, 76.889);
        int lot = insertLot(shop, 3.0, "Выпечка");
        int needy = insertNeedy("Получатель");
        needyService.createTicket(needy, "хлеб", "адрес", 43.24, 76.90, null, lot,
            null, null, null, false);
        assertThat(lotQuantity(lot)).isEqualTo(2.0);

        // Second attempt fails on the one-active-ticket rule after the decrement.
        assertThatThrownBy(() -> needyService.createTicket(needy, "ещё", "адрес", 43.24, 76.90,
            null, lot, null, null, null, false))
            .isInstanceOf(NeedyService.TicketCreateException.class);

        assertThat(lotQuantity(lot)).as("единица не должна потеряться").isEqualTo(2.0);
    }

    @Test
    @DisplayName("недельный лимит закрывает создание заявки")
    void weeklyLimitBlocksANewTicket() {
        int shop = insertShop("Магазин", 43.238, 76.889);
        int lot = insertLot(shop, 5.0, "Выпечка");
        int needy = insertNeedy("Получатель");
        needyService.setProfileLastReceived(needy, OffsetDateTime.now().minusDays(2));

        assertThatThrownBy(() -> needyService.createTicket(needy, "хлеб", "адрес", 43.24, 76.90,
            null, lot, null, null, null, false))
            .isInstanceOf(NeedyService.TicketCreateException.class)
            .hasMessageContaining("weekly_limit");
        assertThat(lotQuantity(lot)).as("отказ до резерва").isEqualTo(5.0);
    }

    // ── cancellation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("отмена заявки возвращает единицу активному лоту")
    void cancellingReturnsTheUnitToAnActiveLot() {
        int shop = insertShop("Магазин", 43.238, 76.889);
        int lot = insertLot(shop, 5.0, "Выпечка");
        int needy = insertNeedy("Получатель");
        int ticket = needyService.createTicket(needy, "хлеб", "адрес", 43.24, 76.90, null, lot,
            null, null, null, false);

        needyService.cancelTicket(needy, ticket);

        assertThat(status("tickets", ticket)).isEqualTo("cancelled");
        assertThat(lotQuantity(lot)).isEqualTo(5.0);
    }

    /** Reserved units must not resurrect into a lot that already left the shelf. */
    @Test
    @DisplayName("отмена не возвращает единицу лоту, который уже забрали")
    void cancellingDoesNotReviveUnitsOfATakenLot() {
        int shop = insertShop("Магазин", 43.238, 76.889);
        int lot = insertLot(shop, 5.0, "Выпечка");
        int needy = insertNeedy("Получатель");
        int ticket = needyService.createTicket(needy, "хлеб", "адрес", 43.24, 76.90, null, lot,
            null, null, null, false);
        jdbc.update("UPDATE lots SET status = 'taken' WHERE id = ?", lot);

        needyService.cancelTicket(needy, ticket);

        assertThat(lotQuantity(lot)).as("guarded-возврат — no-op для taken").isEqualTo(4.0);
    }

    // ── displacement counter (§59/Q1-C) ────────────────────────────────────────

    /**
     * The regression the previous review asked for by name: getting helped must
     * clear displaced_count, or the +3.0×N bonus becomes a permanent rent that
     * parks one recipient at the top of the queue forever.
     */
    @Test
    @DisplayName("выдача помощи сбрасывает счётчик вытеснений")
    void fulfilmentResetsTheDisplacementCounter() {
        int needy = insertNeedy("Получатель");
        jdbc.update("UPDATE needy_profile SET displaced_count = 4 WHERE needy_id = ?", needy);

        needyService.setProfileLastReceived(needy, OffsetDateTime.now());

        assertThat(jdbc.queryForObject(
            "SELECT displaced_count FROM needy_profile WHERE needy_id = ?", Integer.class, needy))
            .isZero();
    }

    /** Without a profile row the weekly limit had nothing to read — so it inserts one. */
    @Test
    @DisplayName("отметка о выдаче создаёт анкету, если её не было")
    void fulfilmentInsertsAProfileWhenMissing() {
        Integer needy = jdbc.queryForObject(
            "INSERT INTO needy (name, status, created_at) "
            + "VALUES ('Без анкеты', 'approved', NOW()) RETURNING id",
            Integer.class);

        needyService.setProfileLastReceived(needy, OffsetDateTime.now());

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM needy_profile WHERE needy_id = ? AND last_received_at IS NOT NULL",
            Integer.class, needy)).isEqualTo(1);
    }

    // ── route teardown (audit item 12) ─────────────────────────────────────────

    @Test
    @DisplayName("маршрут снят до магазина — лот возвращается на витрину, заявки открываются")
    void teardownBeforePickupReturnsTheLot() {
        int shop = insertShop("Магазин", 43.238, 76.889);
        int lot = insertLot(shop, 5.0, "Выпечка");
        int needy = insertNeedy("Получатель");
        int ticket = needyService.createTicket(needy, "хлеб", "адрес", 43.24, 76.90, null, lot,
            null, null, null, false);
        int volunteer = insertVolunteer("Волонтёр");
        claim(lot, ticket, volunteer);

        String points = """
            [{"kind":"shop","lat":43.238,"lon":76.889},
             {"kind":"ticket","ticket_id":%d,"lat":43.24,"lon":76.90}]
            """.formatted(ticket);
        revert.revertRouteLot(lot, points);

        assertThat(status("lots", lot)).isEqualTo("active");
        assertThat(status("tickets", ticket)).isEqualTo("open");
    }

    /**
     * The food is already in the volunteer's car. Returning the lot would
     * advertise stock the shop does not have, and would close the shop's
     * «Подтвердить передачу» window (it requires status='taken').
     */
    @Test
    @DisplayName("маршрут снят после «Забрал» — лот остаётся taken, заявки отменяются")
    void teardownAfterPickupKeepsTheLotTaken() {
        int shop = insertShop("Магазин", 43.238, 76.889);
        int lot = insertLot(shop, 5.0, "Выпечка");
        int needy = insertNeedy("Получатель");
        int ticket = needyService.createTicket(needy, "хлеб", "адрес", 43.24, 76.90, null, lot,
            null, null, null, false);
        int volunteer = insertVolunteer("Волонтёр");
        claim(lot, ticket, volunteer);

        String points = """
            [{"kind":"shop","lat":43.238,"lon":76.889,"done":true},
             {"kind":"ticket","ticket_id":%d,"lat":43.24,"lon":76.90}]
            """.formatted(ticket);
        revert.revertRouteLot(lot, points);

        assertThat(status("lots", lot)).as("еда уехала — лот не возвращается").isEqualTo("taken");
        assertThat(status("tickets", ticket)).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE needy_id = ? AND type = 'ticket_cancelled'",
            Integer.class, needy)).isEqualTo(1);
    }

    /** Lot already confirmed/removed: tickets must not strand 'open' on a dead lot. */
    @Test
    @DisplayName("лот подтверждён магазином до снятия маршрута — заявки отменяются")
    void teardownOnAConfirmedLotCancelsTickets() {
        int shop = insertShop("Магазин", 43.238, 76.889);
        int lot = insertLot(shop, 5.0, "Выпечка");
        int needy = insertNeedy("Получатель");
        int ticket = needyService.createTicket(needy, "хлеб", "адрес", 43.24, 76.90, null, lot,
            null, null, null, false);
        int volunteer = insertVolunteer("Волонтёр");
        claim(lot, ticket, volunteer);
        jdbc.update("UPDATE lots SET status = 'confirmed' WHERE id = ?", lot);

        String points = """
            [{"kind":"shop","lat":43.238,"lon":76.889},
             {"kind":"ticket","ticket_id":%d,"lat":43.24,"lon":76.90}]
            """.formatted(ticket);
        revert.revertRouteLot(lot, points);

        assertThat(status("lots", lot)).isEqualTo("confirmed");
        assertThat(status("tickets", ticket)).isEqualTo("cancelled");
    }

    /** Simulate start_route's claim without pulling in the whole VolunteerService. */
    private void claim(int lotId, int ticketId, int volunteerId) {
        jdbc.update("UPDATE lots SET status = 'taken', taken_at = NOW() WHERE id = ?", lotId);
        jdbc.update("UPDATE tickets SET status = 'assigned', assigned_volunteer_id = ? WHERE id = ?",
            volunteerId, ticketId);
    }
}
