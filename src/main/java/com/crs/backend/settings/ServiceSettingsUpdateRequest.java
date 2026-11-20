package com.crs.backend.settings;

/**
 * openapi.yaml ServiceSettingsUpdateRequest. concurrencyLimit만 받는다(다른 필드는 400 E09, BE-01 설정).
 * 빠진 필드를 알아내려고 Integer(null 허용)로 받는다.
 */
public record ServiceSettingsUpdateRequest(Integer concurrencyLimit) {
}
