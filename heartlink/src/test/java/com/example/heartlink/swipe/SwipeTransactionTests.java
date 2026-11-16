package com.example.heartlink.swipe;

import com.example.heartlink.common.error.ApiException;
import com.example.heartlink.entity.Swipe;
import com.example.heartlink.entity.SwipeAction;
import com.example.heartlink.entity.User;
import com.example.heartlink.match.MatchService;
import com.example.heartlink.repository.SwipeRepository;
import com.example.heartlink.repository.UserRepository;
import com.example.heartlink.swipe.dto.SwipeRequest;
import com.example.heartlink.swipe.dto.SwipeResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises Spring's actual transaction proxy, without a running database. */
class SwipeTransactionTests {
    private final UserRepository users = mock(UserRepository.class);
    private final SwipeRepository swipes = mock(SwipeRepository.class);
    private final MatchService matches = mock(MatchService.class);
    private final SwipeLockManager locks = mock(SwipeLockManager.class);
    private final Connection connection = mock(Connection.class);
    private SwipeService service;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);

        var advice = new TransactionInterceptor();
        advice.setTransactionManager(new DataSourceTransactionManager(dataSource));
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(new SwipeService(users, swipes, matches, locks, new SwipeLockMetrics()));
        proxy.addAdvice(advice);
        service = (SwipeService) proxy.getProxy();

        when(users.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(users.findById(2L)).thenReturn(Optional.of(User.builder().id(2L).build()));
        doAnswer(invocation -> {
            assertActiveTransaction();
            return null;
        }).when(locks).acquirePairLock(1L, 2L);
        when(swipes.saveAndFlush(any())).thenAnswer(invocation -> {
            assertActiveTransaction();
            Swipe swipe = invocation.getArgument(0);
            swipe.setId(10L);
            return swipe;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothEntryPointsKeepLockAndSwipeWriteInsideTransaction(boolean threeArguments) throws Exception {
        SwipeResponse result = invoke(threeArguments, "pass");

        assertThat(result.swipeId()).isEqualTo(10L);
        verify(locks).acquirePairLock(1L, 2L);
        verify(connection).commit();
        verify(connection, never()).rollback();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothEntryPointsRollBackWhenMatchCreationFails(boolean threeArguments) throws Exception {
        when(swipes.existsBySwiperIdAndTargetIdAndAction(2L, 1L, SwipeAction.LIKE)).thenReturn(true);
        var failure = new IllegalStateException("Match creation failed");
        when(matches.createForMutualLike(any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> invoke(threeArguments, "like", "rollback-key")).isSameAs(failure);

        verify(swipes).saveAndFlush(any());
        verify(connection).rollback();
        verify(connection, never()).commit();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothEntryPointsRollBackOnConcurrentKeyReuse(boolean threeArguments) throws Exception {
        var failure = new DataIntegrityViolationException("Duplicate key",
                new ConstraintViolationException("Duplicate key", new SQLException("Duplicate key", "23505"),
                        "uq_swipe_idempotency_key"));
        doThrow(failure).when(swipes).saveAndFlush(any());

        assertThatThrownBy(() -> invoke(threeArguments, "like", "conflicting-key"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));

        verify(connection).rollback();
        verify(connection, never()).commit();
        verifyNoInteractions(matches);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private SwipeResponse invoke(boolean threeArguments, String action) {
        return invoke(threeArguments, action, null);
    }

    private SwipeResponse invoke(boolean threeArguments, String action, String key) {
        var request = new SwipeRequest(2L, action, threeArguments ? null : key);
        return threeArguments ? service.swipe(1L, request, key) : service.swipe(1L, request);
    }

    private void assertActiveTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
    }
}
