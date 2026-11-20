package com.crs.backend.settings;

/**
 * openapi.yaml ServiceSettings. 설정과 조회 시점 슬롯 현황 스냅샷.
 * lastChangedBy·lastChangedAt은 초기 상태에서 null이며, 필수 필드라 null이어도 응답에 넣는다.
 *
 * @param activeSlots    현재 점유 중인 슬롯 수(PROCESSING 티켓 수). N을 낮춘 직후에는 N보다 클 수 있다
 * @param availableSlots max(concurrencyLimit − activeSlots, 0)
 * @param waitingCount   WAITING 티켓 수
 */
public record ServiceSettingsView(
        int concurrencyLimit,
        int activeSlots,
        int availableSlots,
        int waitingCount,
        String lastChangedBy,
        String lastChangedAt
) {
}
