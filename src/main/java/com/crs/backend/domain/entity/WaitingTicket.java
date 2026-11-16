package com.crs.backend.domain.entity;

import com.crs.backend.domain.enums.TicketRequestType;
import com.crs.backend.domain.enums.TicketResultCode;
import com.crs.backend.domain.enums.TicketStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * ENT-04 대기 티켓·결과.
 * 물리명: ticket_id, request_type, requester_user_id, session_ref, ...
 */
@Entity
@Table(name = "waiting_tickets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WaitingTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false)
    private TicketRequestType requestType;

    @Column(name = "requester_user_id", length = 20)
    private String requesterUserId;

    @Column(name = "session_ref", length = 64)
    private String sessionRef;

    @Column(name = "anon_context_id", length = 64)
    private String anonContextId;

    @Column(name = "anon_proof_verifier")
    private String anonProofVerifier;

    @Column(name = "credential_expires_at")
    private Instant credentialExpiresAt;

    @Column(name = "proof_expires_at")
    private Instant proofExpiresAt;

    @Column(name = "target_course_inst_id", length = 36)
    private String targetCourseInstId;

    @Column(name = "course_code_snapshot", length = 10)
    private String courseCodeSnapshot;

    @Column(name = "ticket_issued_at", nullable = false)
    private Instant ticketIssuedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_cd", length = 20, nullable = false)
    private TicketStatus status;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "processing_finished_at")
    private Instant processingFinishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_cd")
    private TicketResultCode resultCode;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "owner_worker_ref")
    private String ownerWorkerRef;

    @Column(name = "generation_no", nullable = false)
    private int generationNo;

    @Column(name = "lease_info")
    private String leaseInfo;

    @Column(name = "slot_claim_ref")
    private String slotClaimRef;

    @Builder
    public WaitingTicket(
            TicketRequestType requestType,
            String requesterUserId,
            String sessionRef,
            String anonContextId,
            String anonProofVerifier,
            Instant credentialExpiresAt,
            Instant proofExpiresAt,
            String targetCourseInstId,
            String courseCodeSnapshot,
            Instant ticketIssuedAt,
            TicketStatus status,
            Instant processingStartedAt,
            Instant processingFinishedAt,
            TicketResultCode resultCode,
            int retryCount,
            String ownerWorkerRef,
            int generationNo,
            String leaseInfo,
            String slotClaimRef
    ) {
        this.requestType = requestType;
        this.requesterUserId = requesterUserId;
        this.sessionRef = sessionRef;
        this.anonContextId = anonContextId;
        this.anonProofVerifier = anonProofVerifier;
        this.credentialExpiresAt = credentialExpiresAt;
        this.proofExpiresAt = proofExpiresAt;
        this.targetCourseInstId = targetCourseInstId;
        this.courseCodeSnapshot = courseCodeSnapshot;
        this.ticketIssuedAt = ticketIssuedAt != null ? ticketIssuedAt : Instant.now();
        this.status = status != null ? status : TicketStatus.RECEIVED;
        this.processingStartedAt = processingStartedAt;
        this.processingFinishedAt = processingFinishedAt;
        this.resultCode = resultCode;
        this.retryCount = retryCount;
        this.ownerWorkerRef = ownerWorkerRef;
        this.generationNo = generationNo;
        this.leaseInfo = leaseInfo;
        this.slotClaimRef = slotClaimRef;
    }

    public static WaitingTicket admission(String requesterUserId, String sessionRef) {
        return WaitingTicket.builder()
                .requestType(TicketRequestType.ADMISSION)
                .requesterUserId(requesterUserId)
                .sessionRef(sessionRef)
                .ticketIssuedAt(Instant.now())
                .status(TicketStatus.WAITING)
                .retryCount(0)
                .generationNo(0)
                .build();
    }

    public static WaitingTicket enrollment(
            String requesterUserId,
            String sessionRef,
            String courseInstId,
            String courseCode
    ) {
        return WaitingTicket.builder()
                .requestType(TicketRequestType.ENROLLMENT)
                .requesterUserId(requesterUserId)
                .sessionRef(sessionRef)
                .targetCourseInstId(courseInstId)
                .courseCodeSnapshot(courseCode)
                .ticketIssuedAt(Instant.now())
                .status(TicketStatus.WAITING)
                .retryCount(0)
                .generationNo(0)
                .build();
    }

    public void markWaiting() {
        this.status = TicketStatus.WAITING;
    }

    public void markProcessing() {
        this.status = TicketStatus.PROCESSING;
        this.processingStartedAt = Instant.now();
    }

    public void succeed() {
        this.status = TicketStatus.SUCCEEDED;
        this.resultCode = TicketResultCode.OK;
        this.processingFinishedAt = Instant.now();
    }

    public void reject(TicketResultCode code) {
        this.status = TicketStatus.REJECTED;
        this.resultCode = code;
        this.processingFinishedAt = Instant.now();
    }

    public boolean isIncomplete() {
        return status == TicketStatus.RECEIVED
                || status == TicketStatus.WAITING
                || status == TicketStatus.PROCESSING;
    }

    public String ticketIdAsString() {
        return String.valueOf(ticketId);
    }
}
