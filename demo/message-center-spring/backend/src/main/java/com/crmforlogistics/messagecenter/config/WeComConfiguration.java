package com.crmforlogistics.messagecenter.config;

import com.crmforlogistics.messagecenter.channel.wecom.WeComAppEventCodec;
import com.crmforlogistics.messagecenter.channel.wecom.WeComCallbackCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComConfiguration {

    @Bean
    public WeComCallbackCodec weComCallbackCodec(AppConfig config) {
        return new WeComCallbackCodec(config);
    }

    /**
     * 应用级回调解码器（客户联系事件通道）。
     *
     * <p>凭据必须是**应用级** Token / EncodingAESKey，与模板级那套是两条独立通道。两者都没配时
     * 本 bean 不注册，端点在拿不到解码器时返回 503 —— 而不是用一个空密钥启动失败。
     */
    @Bean
    @ConditionalOnExpression("not '${app.wecom-app-token:}'.isBlank() "
            + "and not '${app.wecom-app-encoding-aes-key:}'.isBlank()")
    public WeComAppEventCodec weComAppEventCodec(AppConfig config) {
        return new WeComAppEventCodec(config);
    }
}
