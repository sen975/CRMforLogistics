package com.crmforlogistics.messagecenter.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 通用设置入口（{@code POST /api/channel-accounts}）的绑定请求。
 *
 * <p>{@code channelType} 刻意<b>不</b>在这里维护白名单。渠道合法性由
 * {@code ChannelTypeRegistry} 判定（未注册 → {@code CHANNEL_ACCOUNT_TYPE_UNSUPPORTED}，400），
 * 否则新增一个渠道就要回来改这个 DTO —— 那正是「新增渠道必须改核心」的一部分。
 * 这里只校验格式（非空、长度）。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateChannelAccountRequest(
        @NotBlank @Size(max = 50) String channelType,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 255) String accountIdentifier,
        @Size(max = 20) Map<@NotBlank @Size(max = 50) String,
                @NotBlank @Size(max = 4096) String> credentials) {
}
