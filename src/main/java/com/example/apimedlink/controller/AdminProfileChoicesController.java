package com.example.apimedlink.controller;

import com.example.apimedlink.dto.ProfileChoicesDto;
import com.example.apimedlink.mapper.AdminUserMapper;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AdminUserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MLink-69: the lists of a practitioner's profile for the back-office ("Nouveau compte", role change). Staff only;
 * the patients' slot search lists (/api/slots/filters) stay theirs.
 */
@RestController
public class AdminProfileChoicesController {

    private final AdminUserService admin;

    public AdminProfileChoicesController(AdminUserService admin) {
        this.admin = admin;
    }

    @GetMapping("/api/admin/profile-choices")
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    public ProfileChoicesDto profileChoices() {
        return AdminUserMapper.toDto(admin.profileChoices());
    }
}
