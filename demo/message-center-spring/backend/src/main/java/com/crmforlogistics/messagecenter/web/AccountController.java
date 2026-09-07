package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.request.ChangePasswordRequest;
import com.crmforlogistics.messagecenter.dto.request.UpdateAccountProfileRequest;
import com.crmforlogistics.messagecenter.dto.response.AccountProfileResponse;
import com.crmforlogistics.messagecenter.dto.response.LoginResponse;
import com.crmforlogistics.messagecenter.service.account.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/account")
public class AccountController {
    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/profile")
    public AccountProfileResponse profile(Authentication authentication) {
        return accounts.profile(userId(authentication));
    }

    @PatchMapping("/profile")
    public AccountProfileResponse updateProfile(Authentication authentication,
                                                @Valid @RequestBody UpdateAccountProfileRequest request) {
        return accounts.updateProfile(userId(authentication), request.displayName());
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AccountProfileResponse uploadAvatar(Authentication authentication,
                                               @RequestPart("file") MultipartFile file) {
        UUID userId = userId(authentication);
        accounts.uploadAvatar(userId, file);
        return accounts.profile(userId);
    }

    @GetMapping("/avatar/content")
    public ResponseEntity<byte[]> avatarContent(Authentication authentication) {
        AccountService.AvatarContent content = accounts.avatarContent(userId(authentication));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header(HttpHeaders.CONTENT_TYPE, content.mimeType())
                .body(content.data());
    }

    @DeleteMapping("/avatar")
    public AccountProfileResponse deleteAvatar(Authentication authentication) {
        UUID userId = userId(authentication);
        accounts.deleteAvatar(userId);
        return accounts.profile(userId);
    }

    @PutMapping("/password")
    public LoginResponse changePassword(Authentication authentication,
                                        @Valid @RequestBody ChangePasswordRequest request,
                                        HttpServletRequest servletRequest) {
        AccountService.SessionResult result = accounts.changePassword(userId(authentication),
                request.currentPassword(), request.newPassword(), request.confirmPassword(),
                servletRequest.getRemoteAddr(), servletRequest.getHeader("User-Agent"));
        return new LoginResponse(result.token(), result.username(), result.roles());
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
