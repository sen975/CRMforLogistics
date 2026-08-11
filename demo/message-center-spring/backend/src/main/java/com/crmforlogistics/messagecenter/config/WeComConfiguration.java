package com.crmforlogistics.messagecenter.config;

import com.crmforlogistics.messagecenter.channel.wecom.WeComCallbackCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComConfiguration {

    @Bean
    public WeComCallbackCodec weComCallbackCodec(AppConfig config) {
        return new WeComCallbackCodec(config);
    }
}
