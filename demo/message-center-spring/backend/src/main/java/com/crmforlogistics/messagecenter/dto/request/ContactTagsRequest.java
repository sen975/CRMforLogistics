package com.crmforlogistics.messagecenter.dto.request;

import java.util.List;

public record ContactTagsRequest(List<ContactTagInput> tags) {
    public record ContactTagInput(String name, String color) {}
}
