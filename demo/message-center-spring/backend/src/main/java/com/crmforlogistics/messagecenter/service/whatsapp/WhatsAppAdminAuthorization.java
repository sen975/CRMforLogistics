package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class WhatsAppAdminAuthorization {
    private final RoleMapper roles;

    public WhatsAppAdminAuthorization(RoleMapper roles) {
        this.roles = roles;
    }

    public void requireAdmin(UUID actorUserId) {
        if (actorUserId == null || roles == null || !roles.userHasRole(actorUserId, "admin")) {
            throw new WhatsAppAuthorizationException("WHATSAPP_ADMIN_REQUIRED", HttpStatus.FORBIDDEN);
        }
    }
}
