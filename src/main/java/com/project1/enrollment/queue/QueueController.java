package com.project1.enrollment.queue;

import com.project1.enrollment.adapter.in.web.dto.AdmissionState;
import com.project1.enrollment.adapter.in.web.dto.Ticket;
import com.project1.enrollment.adapter.in.web.dto.TicketReceipt;
import com.project1.enrollment.domain.enums.TicketStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대기열 API — AUT-02-01, QUE-01-01, QUE-01-02
 * 기준 경로: /api/v1
 */
@RestController
@RequestMapping("/api/v1")
public class QueueController {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String SESSION_ID_HEADER = "X-Session-Id";

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping("/admission")
    public ResponseEntity<?> requestAdmission(
            @RequestHeader(USER_ID_HEADER) String userId,
            @RequestHeader(value = SESSION_ID_HEADER, required = false) String sessionId
    ) {
        Object result = queueService.requestAdmission(userId, sessionId);
        if (result instanceof AdmissionState state) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(state);
        }
        TicketReceipt receipt = (TicketReceipt) result;
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, receipt.statusUrl())
                .header(HttpHeaders.RETRY_AFTER, "2")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(receipt);
    }

    @GetMapping("/tickets/{ticketId}")
    public ResponseEntity<Ticket> getTicket(@PathVariable String ticketId) {
        Ticket ticket = queueService.getTicket(ticketId);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (ticket.status() == TicketStatus.WAITING
                || ticket.status() == TicketStatus.RECEIVED
                || ticket.status() == TicketStatus.PROCESSING) {
            builder.header(HttpHeaders.RETRY_AFTER, "2");
        }
        return builder.body(ticket);
    }
}
