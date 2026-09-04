package ru.savefood.it;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.savefood.security.PasswordService;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.VolunteerRepository;
import ru.savefood.volunteer.VolunteerService;
import ru.savefood.web.ApiException;
/** PostgreSQL regression coverage for atomic volunteer team assignment. */
class VolunteerTeamConcurrencyIT extends PostgresIT {
    private VolunteerService service;
    private ExecutorService executor;
    @BeforeEach
    void wire() {
        service = new VolunteerService(jdbc, new VolunteerRepository(jdbc),
            new RouteRevertService(jdbc), new PasswordService(), null, null, "Europe/Moscow");
        executor = Executors.newFixedThreadPool(2);
    }
    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }
    @Test
    void twoConcurrentCreatesHaveOneWinnerAndNoOrphanTeam() throws Exception {
        int volunteerId = insertVolunteer("Волонтёр");
        List<Attempt> attempts = race(
            () -> service.createTeam(volunteerId, "Первая команда"),
            () -> service.createTeam(volunteerId, "Вторая команда"));
        assertOneWinner(attempts, volunteerId);
        assertThat(count("teams")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM teams t WHERE NOT EXISTS "
            + "(SELECT 1 FROM volunteers v WHERE v.team_id = t.id)", Integer.class)).isZero();
    }
    @Test
    void concurrentCreateAndJoinHaveOneWinner() throws Exception {
        int volunteerId = insertVolunteer("Волонтёр");
        Team target = insertTeam("Готовая команда", "JOIN01");
        List<Attempt> attempts = race(
            () -> service.createTeam(volunteerId, "Новая команда"),
            () -> service.joinTeam(volunteerId, target.code()));
        assertOneWinner(attempts, volunteerId);
        assertThat(jdbc.queryForObject(
            "SELECT name FROM teams WHERE id = ?", String.class, target.id()))
            .isEqualTo("Готовая команда");
        assertThat(count("teams")).isBetween(1, 2);
    }
    @Test
    void twoConcurrentJoinsHaveOneWinnerAndDoNotModifyTeams() throws Exception {
        int volunteerId = insertVolunteer("Волонтёр");
        Team first = insertTeam("Первая", "JOIN01");
        Team second = insertTeam("Вторая", "JOIN02");
        List<Attempt> attempts = race(
            () -> service.joinTeam(volunteerId, first.code()),
            () -> service.joinTeam(volunteerId, second.code()));
        assertOneWinner(attempts, volunteerId);
        assertThat(count("teams")).isEqualTo(2);
        assertThat(volunteerTeam(volunteerId)).isIn(first.id(), second.id());
    }
    @Test
    void retryAfterSuccessAlwaysConflictsWithoutChangingMembershipOrLeavingOrphans() {
        int volunteerId = insertVolunteer("Волонтёр");
        Team joinTarget = insertTeam("Другая команда", "JOIN01");
        Map<String, Object> created = inTransaction(
            () -> service.createTeam(volunteerId, "Моя команда"));
        int assignedTeam = ((Number) created.get("id")).intValue();
        assertConflict(() -> inTransaction(() -> service.createTeam(volunteerId, "Повтор")));
        assertConflict(() -> inTransaction(() -> service.joinTeam(volunteerId, joinTarget.code())));
        assertThat(volunteerTeam(volunteerId)).isEqualTo(assignedTeam);
        assertThat(count("teams")).isEqualTo(2);
    }
    @Test
    void normalCreateAndJoinStillWork() {
        int creator = insertVolunteer("Создатель");
        int member = insertVolunteer("Участник");
        Map<String, Object> created = inTransaction(
            () -> service.createTeam(creator, "Обычная команда"));
        String code = (String) created.get("join_code");
        Map<String, Object> joined = inTransaction(() -> service.joinTeam(member, code));
        int teamId = ((Number) created.get("id")).intValue();
        assertThat(((Number) joined.get("id")).intValue()).isEqualTo(teamId);
        assertThat(volunteerTeam(creator)).isEqualTo(teamId);
        assertThat(volunteerTeam(member)).isEqualTo(teamId);
        assertThat(((Number) joined.get("members")).intValue()).isEqualTo(2);
    }
    private List<Attempt> race(Supplier<Map<String, Object>> first,
                               Supplier<Map<String, Object>> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Attempt> firstResult = executor.submit(() -> attempt(first, ready, start));
        Future<Attempt> secondResult = executor.submit(() -> attempt(second, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        return List.of(firstResult.get(5, TimeUnit.SECONDS), secondResult.get(5, TimeUnit.SECONDS));
    }
    private Attempt attempt(Supplier<Map<String, Object>> operation,
                            CountDownLatch ready, CountDownLatch start) {
        try {
            Map<String, Object> team = tx.execute(ignored -> {
                ready.countDown();
                await(start);
                return operation.get();
            });
            return new Attempt(true, 200, ((Number) team.get("id")).intValue());
        } catch (ApiException e) {
            return new Attempt(false, e.getStatus(), null);
        }
    }
    private void assertOneWinner(List<Attempt> attempts, int volunteerId) {
        assertThat(attempts).filteredOn(Attempt::success).hasSize(1);
        assertThat(attempts).filteredOn(attempt -> !attempt.success())
            .singleElement().extracting(Attempt::status).isEqualTo(409);
        Integer assigned = volunteerTeam(volunteerId);
        assertThat(assigned).isNotNull();
        assertThat(attempts).filteredOn(Attempt::success)
            .singleElement().extracting(Attempt::teamId).isEqualTo(assigned);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM volunteers WHERE id = ? AND team_id IS NOT NULL",
            Integer.class, volunteerId)).isEqualTo(1);
    }
    private void assertConflict(Runnable operation) {
        assertThatThrownBy(operation::run)
            .isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getStatus()).isEqualTo(409));
    }
    private Map<String, Object> inTransaction(Supplier<Map<String, Object>> operation) {
        return tx.execute(ignored -> operation.get());
    }
    private Team insertTeam(String name, String code) {
        int id = jdbc.queryForObject(
            "INSERT INTO teams (name, join_code) VALUES (?, ?) RETURNING id",
            Integer.class, name, code);
        return new Team(id, code);
    }
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
    private Integer volunteerTeam(int volunteerId) {
        return jdbc.queryForObject(
            "SELECT team_id FROM volunteers WHERE id = ?", Integer.class, volunteerId);
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out coordinating team assignment race");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating team assignment race", e);
        }
    }
    private record Attempt(boolean success, int status, Integer teamId) {
    }
    private record Team(int id, String code) {
    }
}
