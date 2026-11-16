package com.project1.enrollment.domain.service;

import com.project1.enrollment.adapter.out.persistence.jpa.CourseJpaRepository;
import com.project1.enrollment.adapter.out.persistence.jpa.EnrollmentJpaRepository;
import com.project1.enrollment.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.project1.enrollment.domain.entity.Course;
import com.project1.enrollment.domain.entity.Enrollment;
import com.project1.enrollment.domain.enums.EnrollmentStatus;
import com.project1.enrollment.domain.enums.TicketResultCode;
import com.project1.enrollment.domain.exception.EnrollmentException;
import com.project1.enrollment.domain.exception.ErrorCode;
import com.project1.enrollment.domain.policy.EnrollmentPolicy;
import com.project1.enrollment.lock.DistributedLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 수강신청 핵심 도메인 서비스 (컨트롤러 비의존).
 * <p>
 * <b>락·트랜잭션 경계</b>
 * <pre>
 *   @DistributedLock(lock:course:{courseInstId})  // AOP: 락 획득
 *     → @Transactional(REQUIRES_NEW)              // TX 시작
 *     → 검증 + active_cnt 갱신 + Enrollment 저장
 *     → TX 커밋
 *   → AOP: 락 해제
 * </pre>
 * Redisson 락으로 과목 단위 직렬화 + DB PESSIMISTIC_WRITE 로 Lost Update 를 이중 차단한다.
 * <p>
 * <b>검증 순서</b>
 * ① 과목 존재(E04) ② 폐강(E05) ③ 중복 신청(E06)
 * ④ 학점 상한(CREDIT_LIMIT_EXCEEDED) ⑤ 잔여 정원(E07)
 */
@Service
@RequiredArgsConstructor
public class EnrollmentService {

    private final CourseJpaRepository courseRepository;
    private final EnrollmentJpaRepository enrollmentRepository;
    private final WaitingTicketJpaRepository waitingTicketRepository;

    /**
     * 학생 수강신청 실행.
     *
     * @param studentId      학번(user_id)
     * @param courseInstId   과목 인스턴스ID (락 키)
     * @param sourceTicketId 근거 티켓번호 (없으면 null)
     */
    @DistributedLock(key = "#courseInstId", keyPrefix = "lock:course:")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Enrollment enrollStudent(String studentId, String courseInstId, Long sourceTicketId) {
        // 락 내부에서 최신 정원/인원을 읽기 위해 비관적 락
        Course course = courseRepository.findByIdForUpdate(courseInstId)
                .orElseThrow(() -> new EnrollmentException(ErrorCode.E04));

        // ①② 폐강
        if (!course.isOpen()) {
            rejectTicket(sourceTicketId, TicketResultCode.E05);
            throw new EnrollmentException(ErrorCode.E05, "Course is closed: " + course.getCourseCode());
        }

        // ③ 중복 신청
        boolean alreadyEnrolled = enrollmentRepository.existsByIdStudentIdAndIdCourseInstIdAndEnrollStatus(
                studentId, courseInstId, EnrollmentStatus.ENROLLED
        );
        if (alreadyEnrolled) {
            rejectTicket(sourceTicketId, TicketResultCode.E06);
            throw new EnrollmentException(ErrorCode.E06, "Already enrolled: " + course.getCourseCode());
        }

        // ④ 학점 상한 (예: 18학점)
        validateCreditLimit(studentId, course);

        // ⑤ 잔여 정원 (active_cnt < max_capacity)
        if (!course.hasVacancy()) {
            rejectTicket(sourceTicketId, TicketResultCode.E07);
            throw new EnrollmentException(ErrorCode.E07, "No remaining seats: " + course.getCourseCode());
        }

        course.increaseActiveCnt();
        courseRepository.saveAndFlush(course);

        Enrollment enrollment = Enrollment.studentEnrollment(studentId, courseInstId, sourceTicketId);
        Enrollment saved = enrollmentRepository.saveAndFlush(enrollment);

        succeedTicket(sourceTicketId);
        return saved;
    }

    /**
     * 학생 수강 취소 실행.
     */
    @DistributedLock(key = "#courseInstId", keyPrefix = "lock:course:")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelStudent(String studentId, String courseInstId, Long sourceTicketId) {
        Course course = courseRepository.findByIdForUpdate(courseInstId)
                .orElseThrow(() -> new EnrollmentException(ErrorCode.E04));

        if (!course.isOpen()) {
            rejectTicket(sourceTicketId, TicketResultCode.E05);
            throw new EnrollmentException(ErrorCode.E05);
        }

        Enrollment enrollment = enrollmentRepository
                .findByIdStudentIdAndIdCourseInstIdAndEnrollStatus(
                        studentId, courseInstId, EnrollmentStatus.ENROLLED
                )
                .orElseThrow(() -> {
                    rejectTicket(sourceTicketId, TicketResultCode.E08);
                    return new EnrollmentException(ErrorCode.E08);
                });

        enrollmentRepository.delete(enrollment);
        course.decreaseActiveCnt();
        courseRepository.saveAndFlush(course);

        succeedTicket(sourceTicketId);
    }

    private void validateCreditLimit(String studentId, Course addingCourse) {
        List<Enrollment> active = enrollmentRepository
                .findByIdStudentIdAndEnrollStatusOrderByIdCourseInstIdAsc(studentId, EnrollmentStatus.ENROLLED);

        List<String> enrolledInstIds = active.stream()
                .map(e -> e.getId().getCourseInstId())
                .toList();

        int currentCredits = courseRepository.findByCourseInstIdIn(enrolledInstIds).stream()
                .mapToInt(Course::getCredit)
                .sum();

        int adding = addingCourse.getCredit() > 0
                ? addingCourse.getCredit()
                : EnrollmentPolicy.DEFAULT_COURSE_CREDITS;

        if (currentCredits + adding > EnrollmentPolicy.MAX_CREDITS_PER_STUDENT) {
            throw new EnrollmentException(
                    ErrorCode.CREDIT_LIMIT_EXCEEDED,
                    "Credit limit exceeded: current=" + currentCredits
                            + ", adding=" + adding
                            + ", max=" + EnrollmentPolicy.MAX_CREDITS_PER_STUDENT
            );
        }
    }

    private void succeedTicket(Long ticketId) {
        if (ticketId == null) {
            return;
        }
        waitingTicketRepository.findById(ticketId).ifPresent(ticket -> {
            ticket.succeed();
            waitingTicketRepository.save(ticket);
        });
    }

    private void rejectTicket(Long ticketId, TicketResultCode code) {
        if (ticketId == null) {
            return;
        }
        waitingTicketRepository.findById(ticketId).ifPresent(ticket -> {
            ticket.reject(code);
            waitingTicketRepository.save(ticket);
        });
    }
}
