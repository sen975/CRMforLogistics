package com.crmforlogistics.messagecenter.service.assistant;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Token estimate for the configured OpenAI tokenizer encoding. */
@Component
public final class AssistantTokenEstimator {

    private final Encoding encoding;

    public AssistantTokenEstimator(@Value("${assistant.token-encoding:o200k_base}") String encodingName) {
        try {
            EncodingType type = EncodingType.fromName(encodingName)
                    .orElseThrow(() -> new IllegalArgumentException("unknown encoding name"));
            this.encoding = Encodings.newDefaultEncodingRegistry().getEncoding(type);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalArgumentException("Unsupported assistant tokenizer encoding: " + encodingName, failure);
        }
    }

    public int count(String text) {
        return encoding.encode(text == null ? "" : text).size();
    }
}
