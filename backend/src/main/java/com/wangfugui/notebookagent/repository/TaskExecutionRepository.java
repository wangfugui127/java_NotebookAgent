package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.TaskExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Transactional
public interface TaskExecutionRepository extends JpaRepository<TaskExecution, String> {

    Optional<TaskExecution> findByDedupKey(String dedupKey);

    List<TaskExecution> findTop50ByStatusOrderByCreatedAtAsc(String status);

    List<TaskExecution> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            String status, Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TaskExecution t set t.status = 'DISPATCHED', t.streamId = :streamId, t.updatedAt = :now "
            + "where t.id = :id and t.status = :expected")
    int markDispatched(@Param("id") String id, @Param("expected") String expected,
                       @Param("streamId") String streamId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TaskExecution t set t.status = 'RUNNING', t.leaseOwner = :owner, "
            + "t.leaseUntil = :leaseUntil, t.attempt = t.attempt + 1, t.errorMessage = null, t.updatedAt = :now "
            + "where t.id = :id and t.attempt < t.maxAttempts and (t.status in ('NEW','DISPATCHED') "
            + "or (t.status = 'RETRYING' and (t.nextAttemptAt is null or t.nextAttemptAt <= :now)) "
            + "or (t.status = 'RUNNING' and (t.leaseUntil is null or t.leaseUntil < :now)))")
    int claim(@Param("id") String id, @Param("owner") String owner,
              @Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TaskExecution t set t.leaseUntil = :leaseUntil, t.updatedAt = :now "
            + "where t.id = :id and t.leaseOwner = :owner and t.status = 'RUNNING' and t.leaseUntil >= :now")
    int renewLease(@Param("id") String id, @Param("owner") String owner,
                   @Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TaskExecution t set t.status = 'SUCCEEDED', t.leaseOwner = null, "
            + "t.leaseUntil = null, t.updatedAt = :now where t.id = :id and t.status = 'RUNNING' "
            + "and t.leaseOwner = :owner and t.leaseUntil >= :now")
    int markSucceeded(@Param("id") String id, @Param("owner") String owner, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TaskExecution t set t.status = 'RETRYING', t.nextAttemptAt = :next, "
            + "t.leaseOwner = null, t.leaseUntil = null, t.errorMessage = :error, t.updatedAt = :now "
            + "where t.id = :id and t.status = 'RUNNING' and t.leaseOwner = :owner and t.leaseUntil >= :now")
    int markRetrying(@Param("id") String id, @Param("next") Instant next,
                     @Param("error") String error, @Param("owner") String owner, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TaskExecution t set t.status = 'DEAD', t.leaseOwner = null, t.leaseUntil = null, "
            + "t.errorMessage = :error, t.updatedAt = :now where t.id = :id and t.status = 'RUNNING' "
            + "and t.leaseOwner = :owner and t.leaseUntil >= :now")
    int markDead(@Param("id") String id, @Param("error") String error,
                 @Param("owner") String owner, @Param("now") Instant now);

    // Also republish abandoned DB tasks if the Redis pending entry was lost.
    List<TaskExecution> findTop50ByStatusAndLeaseUntilLessThanOrderByLeaseUntilAsc(String status, Instant now);

    List<TaskExecution> findTop50ByStatusAndUpdatedAtLessThanOrderByUpdatedAtAsc(String status, Instant cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TaskExecution t set t.status = 'RETRYING', t.nextAttemptAt = :now, t.leaseOwner = null, "
            + "t.leaseUntil = null, t.updatedAt = :now where t.id = :id and t.status = 'RUNNING' and t.leaseUntil < :now")
    int recoverExpired(@Param("id") String id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TaskExecution t set t.status = 'NEW', t.updatedAt = :now "
            + "where t.id = :id and t.status = 'DISPATCHED' and t.updatedAt < :cutoff")
    int recoverDispatched(@Param("id") String id, @Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TaskExecution t set t.status = 'DEAD', t.errorMessage = :error, t.updatedAt = :now "
            + "where t.id = :id and t.attempt >= t.maxAttempts and t.status in ('NEW','DISPATCHED','RETRYING')")
    int markExhausted(@Param("id") String id, @Param("error") String error, @Param("now") Instant now);
}
