package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.service.channel.ChannelAccountException;
import org.springframework.http.HttpStatus;

public class WhatsAppAuthorizationException extends ChannelAccountException {
    public WhatsAppAuthorizationException(String code, HttpStatus status) {
        super(code, status);
    }
}
