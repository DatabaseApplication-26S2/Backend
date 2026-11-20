package com.crs.backend.settings;

import com.crs.backend.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 설정 API(ADM-02-01). /api/v1/admin/** 는 보안 필터가 ADMIN만 통과시킨다 */
@RestController
@RequestMapping("/api/v1/admin/settings")
public class ServiceSettingsController {

    private final ServiceSettingsService service;

    public ServiceSettingsController(ServiceSettingsService service) {
        this.service = service;
    }

    /** GET /admin/settings : 설정과 슬롯 현황 → 200 */
    @GetMapping
    public ServiceSettingsView get(@RequestAttribute("currentUser") CurrentUser user) {
        return service.get(user);
    }

    /** PUT /admin/settings : 동시 처리 수 N 변경 → 200 */
    @PutMapping
    public ServiceSettingsView update(@RequestAttribute("currentUser") CurrentUser user,
                                      @RequestBody ServiceSettingsUpdateRequest request) {
        return service.update(user, request);
    }
}
