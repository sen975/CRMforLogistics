package com.crmforlogistics.messagecenter;

import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;

import java.time.Duration;
import java.util.List;

interface ChatAppMessageGateway extends AutoCloseable {
    MessagePage listMessages(MessageRequest request, Duration timeout) throws Exception;

    @Override
    void close();

    record MessageRequest(long startTime, long endTime, int pageIndex, int pageSize,
                          String custSpaceId, String channelType, String businessNumber,
                          String userNumber, String messageStatus, String clientAcceptStatus) {
    }

    record MessagePage(List<ListChatappMessageResponseBody.Data> messages) {
    }

    interface Factory {
        ChatAppMessageGateway open() throws Exception;
    }
}
