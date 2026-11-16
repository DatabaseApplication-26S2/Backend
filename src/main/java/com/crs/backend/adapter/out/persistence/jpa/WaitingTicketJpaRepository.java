package com.crs.backend.adapter.out.persistence.jpa;

import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;

public interface WaitingTicketJpaRepository extends JpaRepository<WaitingTicket, Long> {
    boolean existsByRequesterUserIdAndStatusIn(String userId, Collection<TicketStatus> statuses);
}
