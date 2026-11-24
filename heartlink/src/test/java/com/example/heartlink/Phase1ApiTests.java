package com.example.heartlink;

import com.example.heartlink.common.error.ApiException;
import com.example.heartlink.common.security.JwtAuthFilter;
import com.example.heartlink.common.security.JwtService;
import com.example.heartlink.entity.User;
import com.example.heartlink.repository.UserRepository;
import com.example.heartlink.swipe.SwipeService;
import com.example.heartlink.swipe.dto.SwipeRequest;
import com.example.heartlink.swipe.dto.SwipeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in: uses only the disposable PostgreSQL instance in docker-compose.test.yml. */
@EnabledIfEnvironmentVariable(named = "HEARTLINK_DB_TESTS", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:55432/heartlink_test",
        "spring.datasource.username=postgres", "spring.datasource.password=test",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "heartlink.swipe.lock-strategy=pair",
        "app.jwt.secret=phase1-test-secret-at-least-32-bytes-long"
})
class Phase1ApiTests {
    @Autowired WebApplicationContext context;
    @Autowired JwtAuthFilter filter;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired SwipeService swipes;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    MockMvc mvc;
    Long a;
    Long b;
    Long c;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        jdbc.update("delete from refresh_tokens");
        jdbc.update("delete from notifications");
        jdbc.update("delete from messages");
        jdbc.update("delete from matches");
        jdbc.update("delete from swipes");
        jdbc.update("delete from users");
        a = user("a");
        b = user("b");
        c = user("c");
    }

    private Long user(String name) {
        return users.saveAndFlush(User.builder().email(name + "@example.com")
                .name(name).bio("bio").passwordHash("unused-in-token-auth-tests").build()).getId();
    }

    private String auth(Long id) { return "Bearer " + jwt.issue(id); }

    @Test
    void chatPersistsBothSidesPaginatesAndClosesOnUnmatch() throws Exception {
        swipes.swipe(a, new SwipeRequest(b, "like"));
        Long matchId = swipes.swipe(b, new SwipeRequest(a, "like")).match().id();
        String path = "/api/v1/matches/" + matchId + "/messages";
        mvc.perform(get(path).header("Authorization", auth(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(post(path).header("Authorization", auth(a)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Chào bạn!\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sender_id").value(a.intValue()))
                .andExpect(jsonPath("$.content").value("Chào bạn!"))
                .andExpect(jsonPath("$.created_at").isString());
        Long firstId = jdbc.queryForObject("select min(id) from messages", Long.class);
        mvc.perform(post(path).header("Authorization", auth(b)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Rất vui được làm quen\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sender_id").value(b.intValue()));
        Long latestId = jdbc.queryForObject("select max(id) from messages", Long.class);
        mvc.perform(get(path + "?limit=1").header("Authorization", auth(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(latestId.intValue()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.next_before").value(latestId.intValue()));
        mvc.perform(get(path + "?limit=1&before=" + latestId).header("Authorization", auth(b)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(firstId.intValue()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"next_before\":null")));
        swipes.swipe(a, new SwipeRequest(c, "like"));
        Long otherMatch = swipes.swipe(c, new SwipeRequest(a, "like")).match().id();
        mvc.perform(get("/api/v1/matches/" + otherMatch + "/messages?before=" + latestId)
                        .header("Authorization", auth(c)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(path).header("Authorization", auth(c))).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization", auth(c)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"not allowed\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/matches/" + matchId).header("Authorization", auth(a)))
                .andExpect(status().isNoContent());
        mvc.perform(get(path).header("Authorization", auth(b))).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", auth(a)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"too late\"}"))
                .andExpect(status().isNotFound());
        assertThat(count("messages")).isEqualTo(2);
    }

    @Test
    void chatRequiresTokenAndValidatesBodyAndPagination() throws Exception {
        swipes.swipe(a, new SwipeRequest(b, "like"));
        Long matchId = swipes.swipe(b, new SwipeRequest(a, "like")).match().id();
        String path = "/api/v1/matches/" + matchId + "/messages";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        for (String body : new String[]{"{}", "null", "{", "{\"content\":null}",
                "{\"content\":\"   \"}", "{\"content\":\"" + "x".repeat(2001) + "\"}"}) {
            mvc.perform(post(path).header("Authorization", auth(a)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        for (String query : new String[]{"limit=0", "limit=51", "limit=abc", "before=0", "before=-1", "before=abc"}) {
            mvc.perform(get(path + "?" + query).header("Authorization", auth(a)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(get("/api/v1/matches/9223372036854775807/messages").header("Authorization", auth(a)))
                .andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", auth(a)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + "x".repeat(2000) + "\"}"))
                .andExpect(status().isCreated());
        assertThat(count("messages")).isEqualTo(1);
    }

    @Test
    void recommendationsExcludeSelfAndBothActionsButKeepIncomingLikes() throws Exception {
        swipes.swipe(b, new SwipeRequest(a, "like"));
        mvc.perform(get("/api/v1/recommendations").header("Authorization", auth(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(b.intValue()))
                .andExpect(jsonPath("$.items[0].email").doesNotExist())
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
        swipes.swipe(a, new SwipeRequest(b, "like"));
        swipes.swipe(a, new SwipeRequest(c, "pass"));
        mvc.perform(get("/api/v1/recommendations").header("Authorization", auth(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void recommendationsEnforceDefaultAndLimit() throws Exception {
        for (int i = 0; i < 55; i++) user("extra" + i);
        mvc.perform(get("/api/v1/recommendations").header("Authorization", auth(a)))
                .andExpect(jsonPath("$.items.length()").value(20));
        mvc.perform(get("/api/v1/recommendations?limit=50").header("Authorization", auth(a)))
                .andExpect(jsonPath("$.items.length()").value(50));
        for (String limit : new String[]{"0", "-1", "51", "abc"}) {
            mvc.perform(get("/api/v1/recommendations?limit=" + limit).header("Authorization", auth(a)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").exists());
        }
    }

    @Test
    void bothEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/recommendations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/swipes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"target_id\":" + b + ",\"action\":\"like\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/recommendations").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validatesRequestAndBusinessErrors() throws Exception {
        for (String body : new String[]{"{}", "null", "{", "{\"target_id\":0,\"action\":\"like\"}",
                "{\"target_id\":" + b + ",\"action\":\"LIKE\"}",
                "{\"target_id\":" + b + ",\"action\":\"undo\"}",
                "{\"target_id\":" + a + ",\"action\":\"like\"}"}) {
            mvc.perform(post("/api/v1/swipes").header("Authorization", auth(a))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").exists());
        }
        mvc.perform(post("/api/v1/swipes").header("Authorization", auth(a))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target_id\":9223372036854775807,\"action\":\"like\"}"))
                .andExpect(status().isNotFound());
        assertThat(count("swipes")).isZero();
    }

    @Test
    void likeCreatesOneCanonicalMatchAndCancelDoesNotAllowReswipe() throws Exception {
        mvc.perform(post("/api/v1/swipes").header("Authorization", auth(b))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target_id\":" + a + ",\"action\":\"like\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.swipe_id").isNumber())
                .andExpect(jsonPath("$.matched").value(false)).andExpect(content().string(org.hamcrest.Matchers.containsString("\"match\":null")));
        mvc.perform(post("/api/v1/swipes").header("Authorization", auth(a))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target_id\":" + b + ",\"action\":\"like\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.match.with_user_id").value(b.intValue()));
        assertThat(count("matches")).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select user_a_id from matches", Long.class)).isEqualTo(a);
        Long matchId = jdbc.queryForObject("select id from matches", Long.class);
        mvc.perform(delete("/api/v1/matches/" + matchId).header("Authorization", auth(a)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/swipes").header("Authorization", auth(a))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target_id\":" + b + ",\"action\":\"pass\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ALREADY_SWIPED"));
        assertThat(count("swipes")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from matches", String.class)).isEqualTo("CANCELLED");
    }

    @Test
    void passDoesNotMatchIncomingLike() {
        swipes.swipe(a, new SwipeRequest(b, "like"));
        assertThat(swipes.swipe(b, new SwipeRequest(a, "pass")).matched()).isFalse();
        assertThat(count("matches")).isZero();
        assertThat(count("notifications")).isZero();
    }

    @Test
    void keyedRetryKeepsOriginalUnmatchedResponseAfterAReciprocalLike() throws Exception {
        String original = keyedSwipe(a, b, "like", "before-match").andExpect(status().isCreated())
                .andExpect(jsonPath("$.matched").value(false)).andReturn().getResponse().getContentAsString();

        assertThat(swipes.swipe(b, new SwipeRequest(a, "like")).matched()).isTrue();

        keyedSwipe(a, b, "like", "before-match").andExpect(status().isCreated())
                .andExpect(content().string(original));
        assertThat(count("swipes")).isEqualTo(2);
        assertThat(count("matches")).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(2);
    }

    @Test
    void keyedRetryKeepsOriginalMatchResponseAfterCancellation() throws Exception {
        swipes.swipe(b, new SwipeRequest(a, "like"));
        String original = keyedSwipe(a, b, "like", "created-match").andExpect(status().isCreated())
                .andExpect(jsonPath("$.matched").value(true)).andReturn().getResponse().getContentAsString();
        Long matchId = jdbc.queryForObject("select id from matches", Long.class);
        assertThat(jdbc.queryForObject("select response_match_id from swipes where swiper_id = ?",
                Long.class, a)).isEqualTo(matchId);

        mvc.perform(delete("/api/v1/matches/" + matchId).header("Authorization", auth(a)))
                .andExpect(status().isNoContent());

        keyedSwipe(a, b, "like", "created-match").andExpect(status().isCreated())
                .andExpect(content().string(original));
        assertThat(jdbc.queryForObject("select status from matches", String.class)).isEqualTo("CANCELLED");
        assertThat(count("swipes")).isEqualTo(2);
        assertThat(count("notifications")).isEqualTo(2);
    }

    @Test
    void keyReuseRequiresTheSameTargetAndAction() throws Exception {
        keyedSwipe(a, b, "like", "fixed-request").andExpect(status().isCreated());

        keyedSwipe(a, c, "like", "fixed-request").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        keyedSwipe(a, b, "pass", "fixed-request").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        keyedSwipe(a, b, "like", "another-key").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_SWIPED"));
        assertThat(count("swipes")).isEqualTo(1);
    }

    @Test
    void bodyKeysWorkAndTheSameKeyIsIndependentForDifferentUsers() throws Exception {
        keyedSwipe(a, b, "like", "shared-key").andExpect(status().isCreated());
        mvc.perform(post("/api/v1/swipes").header("Authorization", auth(b))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_id\":" + a + ",\"action\":\"like\",\"idempotency_key\":\"shared-key\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.matched").value(true));
        assertThat(count("swipes")).isEqualTo(2);
        assertThat(count("matches")).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(2);
    }

    @Test
    void validatesHeaderAndBodyKeys() throws Exception {
        for (String key : new String[]{"", "has space", "k".repeat(129)}) {
            keyedSwipe(a, b, "like", key).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
            mvc.perform(post("/api/v1/swipes").header("Authorization", auth(a))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"target_id\":" + b + ",\"action\":\"like\",\"idempotency_key\":\"" + key + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/swipes").header("Authorization", auth(a))
                        .header("Idempotency-Key", "header-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_id\":" + b + ",\"action\":\"like\",\"idempotency_key\":\"body-key\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(count("swipes")).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentIdenticalKeysReturnTheSameOriginalResponse(boolean incomingLike) throws Exception {
        if (incomingLike) {
            swipes.swipe(b, new SwipeRequest(a, "like"));
        }
        var gate = new CyclicBarrier(2);
        java.util.concurrent.Callable<SwipeResponse> request = () -> {
            gate.await(5, TimeUnit.SECONDS);
            return swipes.swipe(a, new SwipeRequest(b, "like", "concurrent-key"));
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(request);
            var second = pool.submit(request);
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(count("swipes")).isEqualTo(incomingLike ? 2 : 1);
        assertThat(count("matches")).isEqualTo(incomingLike ? 1 : 0);
        assertThat(count("notifications")).isEqualTo(incomingLike ? 2 : 0);
    }

    @Test
    void concurrentKeyReuseAcrossDifferentPairsCreatesOnlyOneNewSwipeAndMatch() throws Exception {
        swipes.swipe(b, new SwipeRequest(a, "like"));
        swipes.swipe(c, new SwipeRequest(a, "like"));
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> concurrentKeyedSwipe(gate, b));
            var second = pool.submit(() -> concurrentKeyedSwipe(gate, c));
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("201", "IDEMPOTENCY_KEY_REUSED");
        }
        assertThat(count("swipes")).isEqualTo(3);
        assertThat(count("matches")).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(2);
    }

    private String concurrentKeyedSwipe(CyclicBarrier gate, Long target) throws Exception {
        gate.await(5, TimeUnit.SECONDS);
        try {
            swipes.swipe(a, new SwipeRequest(target, "like", "same-key-different-pairs"));
            return "201";
        } catch (ApiException exception) {
            return exception.getCode();
        }
    }

    @Test
    void concurrentReciprocalLikesCreateExactlyOneMatch() throws Exception {
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(5, TimeUnit.SECONDS); return swipes.swipe(a, new SwipeRequest(b, "like")); });
            var second = pool.submit(() -> { gate.await(5, TimeUnit.SECONDS); return swipes.swipe(b, new SwipeRequest(a, "like")); });
            SwipeResponse x = first.get(15, TimeUnit.SECONDS);
            SwipeResponse y = second.get(15, TimeUnit.SECONDS);
            assertThat(x.matched() ^ y.matched()).isTrue();
        }
        assertThat(count("swipes")).isEqualTo(2);
        assertThat(count("matches")).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(2);
    }

    @Test
    void heldPairLockBlocksReciprocalSwipeButAllowsOtherHotProfilePairs() throws Exception {
        var firstWritten = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var reciprocalBackend = new CompletableFuture<Integer>();
        try (var pool = Executors.newFixedThreadPool(3)) {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var response = swipes.swipe(b, new SwipeRequest(a, "like"));
                firstWritten.countDown();
                try {
                    assertThat(releaseFirst.await(15, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return response;
            }));

            java.util.concurrent.Future<SwipeResponse> reciprocal;
            try {
                assertThat(firstWritten.await(5, TimeUnit.SECONDS)).isTrue();
                reciprocal = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    reciprocalBackend.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    return swipes.swipe(a, new SwipeRequest(b, "like"));
                }));
                awaitAdvisoryLockWait(reciprocalBackend.get(5, TimeUnit.SECONDS));
                assertThat(reciprocal.isDone()).isFalse();

                // C -> A must finish while B -> A still holds its transaction and A -> B waits.
                var independent = pool.submit(() -> swipes.swipe(c, new SwipeRequest(a, "like")));
                assertThat(independent.get(5, TimeUnit.SECONDS).matched()).isFalse();
                assertThat(first.isDone()).isFalse();
                assertThat(reciprocal.isDone()).isFalse();
            } finally {
                releaseFirst.countDown();
            }

            assertThat(first.get(5, TimeUnit.SECONDS).matched()).isFalse();
            assertThat(reciprocal.get(5, TimeUnit.SECONDS).matched()).isTrue();
        }
        assertThat(count("swipes")).isEqualTo(3);
        assertThat(count("matches")).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(2);
    }

    private void awaitAdvisoryLockWait(int backendPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists (select 1 from pg_locks
                                   where pid = ? and locktype = 'advisory' and not granted)
                    """, Boolean.class, backendPid))) {
                return;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Reciprocal swipe never waited on the held PostgreSQL advisory lock");
    }

    @Test
    void concurrentDuplicateSwipesReturnCreatedAndConflict() throws Exception {
        var gate = new CyclicBarrier(2);
        java.util.concurrent.Callable<Integer> call = () -> {
            gate.await(5, TimeUnit.SECONDS);
            try { swipes.swipe(a, new SwipeRequest(b, "like")); return 201; }
            catch (ApiException e) { return e.getStatus().value(); }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(call);
            var second = pool.submit(call);
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(count("swipes")).isEqualTo(1);
    }

    @Test
    void failedMatchInsertRollsBackNewSwipe() {
        swipes.swipe(b, new SwipeRequest(a, "like"));
        jdbc.execute("alter table matches add constraint test_reject_match check (id < 0)");
        try {
            assertThatThrownBy(() -> swipes.swipe(a, new SwipeRequest(b, "like", "failed-match")))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(count("swipes")).isEqualTo(1);
            assertThat(count("matches")).isZero();
            assertThat(count("notifications")).isZero();
        } finally {
            jdbc.execute("alter table matches drop constraint test_reject_match");
        }
        assertThat(swipes.swipe(a, new SwipeRequest(b, "like", "failed-match")).matched()).isTrue();
        assertThat(count("swipes")).isEqualTo(2);
        assertThat(count("matches")).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(2);
    }

    @Test
    void failedNotificationInsertRollsBackMatchAndNewSwipe() {
        swipes.swipe(b, new SwipeRequest(a, "like"));
        jdbc.execute("alter table notifications add constraint test_reject_notification check (id < 0)");
        try {
            assertThatThrownBy(() -> swipes.swipe(a, new SwipeRequest(b, "like", "failed-notification")))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(count("swipes")).isEqualTo(1);
            assertThat(count("matches")).isZero();
            assertThat(count("notifications")).isZero();
        } finally {
            jdbc.execute("alter table notifications drop constraint test_reject_notification");
        }
        assertThat(swipes.swipe(a, new SwipeRequest(b, "like", "failed-notification")).matched()).isTrue();
        assertThat(count("swipes")).isEqualTo(2);
        assertThat(count("matches")).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(2);
    }

    private org.springframework.test.web.servlet.ResultActions keyedSwipe(Long user, Long target, String action, String key)
            throws Exception {
        return mvc.perform(post("/api/v1/swipes").header("Authorization", auth(user))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"target_id\":" + target + ",\"action\":\"" + action + "\"}"));
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
