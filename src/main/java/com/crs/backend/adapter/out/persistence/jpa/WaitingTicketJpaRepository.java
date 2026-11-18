package com.crs.backend.adapter.out.persistence.jpa;

import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.TicketStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WaitingTicketJpaRepository extends JpaRepository<WaitingTicket, Long> {

    boolean existsByRequesterUserIdAndStatusIn(String userId, Collection<TicketStatus> statuses);

    Optional<WaitingTicket> findFirstByRequesterUserIdAndStatusIn(
            String requesterUserId,
            Collection<TicketStatus> statuses
    );

    long countByStatus(TicketStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from WaitingTicket t where t.status = :status order by t.ticketId asc")
    List<WaitingTicket> findByStatusForClaim(@Param("status") TicketStatus status, Pageable pageable);

    @Query("""
            select count(t) from WaitingTicket t
            where t.status = com.crs.backend.domain.enums.TicketStatus.WAITING
              and t.ticketId < :ticketId
            """)
    long countWaitingAhead(@Param("ticketId") Long ticketId);

    @Query("""
            select count(t) > 0 from WaitingTicket t
            where t.targetCourseInstId = :courseInstId
              and t.ticketId < :ticketId
              and t.status in :statuses
            """)
    boolean existsEarlierIncompleteForCourse(
            @Param("courseInstId") String courseInstId,
            @Param("ticketId") Long ticketId,
            @Param("statuses") Collection<TicketStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from WaitingTicket t where t.ticketId = :ticketId")
    Optional<WaitingTicket> findByIdForUpdate(@Param("ticketId") Long ticketId);
}
