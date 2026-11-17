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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * ENT-04 대기 티켓·결과.
 * 물리명: ticket_id, request_type, requester_user_id, session_ref, ...
 * 테이블 tickets(구현계획 3.4). uuid 컬럼은 Java에서 String으로 다루고,
 * PostgreSQL 드라이버 설정 stringtype=unspecified로 uuid에 바인딩한다.
 */
@Entity
@Table(name = "tickets")
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

    @Column(name = "session_ref", columnDefinition = "uuid")
    private String sessionRef;

    @Column(name = "anon_context_id", columnDefinition = "uuid")
    private String anonContextId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "anon_proof_verifier", length = 64)
    private String anonProofVerifier;

    @Column(name = "credential_expires_at")
    private Instant credentialExpiresAt;

    @Column(name = "proof_expires_at")
    private Instant proofExpiresAt;

    @Column(name = "target_course_inst_id", columnDefinition = "uuid")
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
    @Column(name = "result_cd", length = 10)
    private TicketResultCode resultCode;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "owner_worker_ref", length = 64)
    private String ownerWorkerRef;

    @Column(name = "generation_no", nullable = false)
    private int generationNo;

    /** 리스 만료 시각(절대 시각) */
    @Column(name = "lease_info")
    private Instant leaseInfo;

    /** 슬롯을 점유한 세대 값. PROCESSING일 때 generation_no와 같다 */
    @Column(name = "slot_claim_ref")
    private Integer slotClaimRef;

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
            Instant leaseInfo,
            Integer slotClaimRef
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

    /** 이 프로세스(인스턴스)의 처리기 식별자. 기동할 때 한 번 만든다(구현계획 4절 (6)) */
    public static final String WORKER_REF = "worker-" + java.util.UUID.randomUUID();

    /** 처리 리스(구현계획 D-07 제안값 30초) */
    public static final java.time.Duration LEASE = java.time.Duration.ofSeconds(30);

    /**
     * PROCESSING 전환. tickets CHECK 3(PROCESSING일 때만 소유·리스·슬롯이 있고 슬롯 = 세대)을 지킨다.
     */
    public void markProcessing() {
        Instant now = Instant.now();
        this.status = TicketStatus.PROCESSING;
        this.processingStartedAt = now;
        this.generationNo = this.generationNo + 1;
        this.slotClaimRef = this.generationNo;
        this.ownerWorkerRef = WORKER_REF;
        this.leaseInfo = now.plus(LEASE);
    }

    public void succeed() {
        this.status = TicketStatus.SUCCEEDED;
        this.resultCode = TicketResultCode.OK;
        this.processingFinishedAt = Instant.now();
        releaseSlot();
    }

    public void reject(TicketResultCode code) {
        this.status = TicketStatus.REJECTED;
        this.resultCode = code;
        this.processingFinishedAt = Instant.now();
        releaseSlot();
    }

    /** 종결하면 소유·리스·슬롯을 비운다(CHECK 3: PROCESSING이 아니면 셋 다 NULL) */
    private void releaseSlot() {
        this.ownerWorkerRef = null;
        this.leaseInfo = null;
        this.slotClaimRef = null;
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
