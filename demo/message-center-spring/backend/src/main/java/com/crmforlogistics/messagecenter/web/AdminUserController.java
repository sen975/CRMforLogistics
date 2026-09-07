package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.request.AdminReplaceRolesRequest;
import com.crmforlogistics.messagecenter.dto.request.AdminResetPasswordRequest;
import com.crmforlogistics.messagecenter.dto.response.AdminUserPageResponse;
import com.crmforlogistics.messagecenter.dto.response.AdminUserResponse;
import com.crmforlogistics.messagecenter.dto.response.RoleResponse;
import com.crmforlogistics.messagecenter.service.account.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminUserController {
    private final AccountService accounts;

    public AdminUserController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/users")
    public AdminUserPageResponse users(Authentication authentication,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return accounts.listUsers(userId(authentication), page, size);
    }

    @GetMapping("/roles")
    public List<RoleResponse> roles(Authentication authentication) {
        return accounts.listRoles(userId(authentication));
    }

    @PutMapping("/users/{targetId}/roles")
    public AdminUserResponse replaceRoles(Authentication authentication,
                                          @PathVariable UUID targetId,
                                          @Valid @RequestBody AdminReplaceRolesRequest request) {
        return accounts.replaceRoles(userId(authentication), targetId, request.roles());
    }

    @PutMapping("/users/{targetId}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(Authentication authentication, @PathVariable UUID targetId,
                              @Valid @RequestBody AdminResetPasswordRequest request) {
        accounts.adminResetPassword(userId(authentication), targetId,
                request.newPassword(), request.confirmPassword());
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
