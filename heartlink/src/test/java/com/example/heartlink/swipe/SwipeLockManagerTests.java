package com.example.heartlink.swipe;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.TransactionRequiredException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SwipeLockManagerTests {
    private final EntityManager entityManager = mock(EntityManager.class);
    private final SwipeLockManager locks = new SwipeLockManager(entityManager);

    @Test
    void rejectsAcquisitionOutsideADatabaseTransaction() {
        assertThatThrownBy(() -> locks.acquirePairLock(1L, 2L))
                .isInstanceOf(TransactionRequiredException.class);

        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    void propagatesDatabaseFailureInsteadOfContinuingWithoutALock() {
        when(entityManager.isJoinedToTransaction()).thenReturn(true);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("key"), anyLong())).thenReturn(query);
        var failure = new PersistenceException("Lock acquisition failed");
        when(query.getSingleResult()).thenThrow(failure);

        assertThatThrownBy(() -> locks.acquirePairLock(1L, 2L)).isSameAs(failure);
    }
}
