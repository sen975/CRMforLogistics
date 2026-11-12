package com.crmforlogistics.messagecenter.channel.chatapp.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ListBaseTemplateFixtureTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void providesAllSanitizedListBaseTemplateFixtures() throws Exception {
        for (String fixture : List.of("list-base-template.json", "list-base-template-empty.json",
                "list-base-template-invalid.json")) {
            Map<String, Object> envelope = readFixture(fixture);
            assertThat(envelope).containsKeys("Code", "RequestId", "Success", "Data");
            assertThat(envelope.get("RequestId")).isInstanceOf(String.class);
        }
    }

    private Map<String, Object> readFixture(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/chatapp/" + name)) {
            return objectMapper.readValue(input, new TypeReference<>() {});
        }
    }
}
