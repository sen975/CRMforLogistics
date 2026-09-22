package com.crmforlogistics.messagecenter.dto.response;

import java.util.UUID;

/**
 * 一次会话偏好变更的结果。
 *
 * <p>{@code displayName} 是「被操作会话的显示名」，取自授权查询本身顺带读到的权威名字
 * （联系人显示名 / 已掩码的群名），供调用方回话使用。
 *
 * <p>为什么由服务端带回来，而不是让调用方拿 {@code targetId} 自己再查一次或自己拼：
 * 助手侧的执行回话原本拼的是 {@code "已置顶会话：" + conversationRef}，于是用户点完「确认」
 * 看到的是 {@code 已置顶会话：CONTACT:d526bde8-…} —— 卡片上明明写着「悦为小森」，紧接着的
 * 回话却是一串 uuid。名字在这次授权查询里**本来就已经读出来了**
 * （见 {@code ConversationPreferenceService#authorize}），丢掉再让别人重查一遍既多一次查询，
 * 也必然出现「卡片有名字、回话没有」的分叉。
 *
 * <p>可能为 {@code null}：拿不到名字时不编造，由调用方退回显示 id。
 */
public record ConversationPreferenceResponse(String targetType, UUID targetId, boolean pinned,
                                             boolean hidden, String displayName) {
}
