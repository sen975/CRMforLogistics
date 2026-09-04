package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.request.CreateChannelContactRequest;
import com.crmforlogistics.messagecenter.dto.response.ChannelAddressBookItem;
import com.crmforlogistics.messagecenter.dto.response.ChannelAddressBookPageResponse;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.contact.ChannelAddressBookService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/channel-address-books/{channelType}")
public class ChannelAddressBookController {
    private final ChannelAddressBookService service;

    public ChannelAddressBookController(ChannelAddressBookService service) {
        this.service = service;
    }

    @GetMapping
    public ChannelAddressBookPageResponse page(
            @PathVariable String channelType,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.page(SecurityUtil.currentUserId(), channelType, query, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChannelAddressBookItem create(@PathVariable String channelType,
                                         @Valid @RequestBody CreateChannelContactRequest request) {
        CreateChannelContactRequest scoped = new CreateChannelContactRequest(
                channelType, request.displayName(), request.address());
        return service.createManual(SecurityUtil.currentUserId(), scoped);
    }

    @DeleteMapping("/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String channelType, @PathVariable UUID contactId) {
        service.requireSupportedChannel(channelType);
        service.deleteManual(SecurityUtil.currentUserId(), contactId);
    }
}
