package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.dto.request.SearchMode;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 标签模式下，把本页联系人命中的标签名批量取回，用于在结果行上解释命中原因。
 * 非标签模式、空关键词、空 id 列表都不查库。
 */
@Service
public class ContactTagMatchResolver {

    private final ContactTagMapper tagMapper;

    public ContactTagMatchResolver(ContactTagMapper tagMapper) {
        this.tagMapper = tagMapper;
    }

    public Map<UUID, List<String>> matchNamesByContact(UUID ownerId, Collection<UUID> contactIds,
                                                       String search, SearchMode searchMode) {
        if (!searchMode.isTag() || search == null || search.isBlank() || contactIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<String>> matched = new LinkedHashMap<>();
        for (ContactTagMapper.MatchedTagRow row
                : tagMapper.findMatchedByContactIds(ownerId, contactIds, search)) {
            matched.computeIfAbsent(row.contactId(), key -> new ArrayList<>()).add(row.name());
        }
        return matched;
    }
}
