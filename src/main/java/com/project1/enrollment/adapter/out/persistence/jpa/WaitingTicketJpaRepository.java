package com.project1.enrollment.adapter.out.persistence.jpa;

import com.project1.enrollment.domain.entity.WaitingTicket;
import com.project1.enrollment.domain.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;

public interface WaitingTicketJpaRepository extends JpaRepository<WaitingTicket, Long> {
    boolean existsByRequesterUserIdAndStatusIn(String userId, Collection<TicketStatus> statuses);
}
