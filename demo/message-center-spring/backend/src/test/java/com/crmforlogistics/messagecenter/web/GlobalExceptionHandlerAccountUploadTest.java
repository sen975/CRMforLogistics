package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerAccountUploadTest {
    @Test
    void oversizedAccountAvatarUsesTheAccountAvatarErrorContract() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/account/avatar");

        ApiError error = new GlobalExceptionHandler().handleUploadTooLarge(
                new MaxUploadSizeExceededException(2L * 1024 * 1024), request);

        assertThat(error.code()).isEqualTo("AVATAR_INVALID");
        assertThat(error.message()).isEqualTo("AVATAR_INVALID");
        assertThat(error.fieldErrors()).containsEntry("file", "exceeds the maximum request size");
    }
}
