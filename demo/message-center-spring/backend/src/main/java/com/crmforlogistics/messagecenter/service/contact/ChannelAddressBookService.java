package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.dto.request.CreateChannelContactRequest;
import com.crmforlogistics.messagecenter.dto.response.ChannelAddressBookItem;
import com.crmforlogistics.messagecenter.dto.response.ChannelAddressBookPageResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ChannelAddressBookService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE = 10_000;
    private static final int MAX_SEARCH_LENGTH = 100;

    private final ContactMapper contacts;
    private final ContactIdentityMapper identities;
    private final ChannelAccountMapper accounts;

    public ChannelAddressBookService(ContactMapper contacts, ContactIdentityMapper identities,
                                     ChannelAccountMapper accounts) {
        this.contacts = contacts;
        this.identities = identities;
        this.accounts = accounts;
    }

    public ChannelAddressBookPageResponse page(UUID ownerId, String rawChannelType, String rawQuery,
                                                int requestedPage, int requestedSize) {
        String channelType = normalizeChannelType(rawChannelType);
        if (requestedPage > MAX_PAGE) {
            throw new ChannelAddressBookException("CONTACT_PAGE_OUT_OF_RANGE", HttpStatus.BAD_REQUEST);
        }
        int page = Math.max(1, requestedPage);
        int size = requestedSize <= 0 ? 20 : Math.min(requestedSize, MAX_PAGE_SIZE);
        String query = normalizeQuery(rawQuery);
        int offset = (page - 1) * size;
        List<ContactMapper.ChannelAddressBookRow> rows = contacts.listAddressBookByOwner(
                ownerId, channelType, query, size + 1, offset);
        boolean hasMore = rows.size() > size;
        List<ChannelAddressBookItem> items = rows.stream().limit(size).map(this::toItem).toList();
        return new ChannelAddressBookPageResponse(items, page, size, hasMore);
    }

    public void requireSupportedChannel(String channelType) {
        normalizeChannelType(channelType);
    }

    @Transactional
    public ChannelAddressBookItem createManual(UUID ownerId, CreateChannelContactRequest request) {
        String channelType = normalizeChannelType(request.channelType());
        String normalized = normalizeAddress(channelType, request.address());
        String scope = identityScope(ownerId, channelType);
        if (identities.findByNormalizedValueInScope(channelType, scope, normalized).isPresent()) {
            throw new ChannelAddressBookException("CONTACT_IDENTITY_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }

        Instant now = Instant.now();
        ContactEntity contact = new ContactEntity();
        contact.setId(UUID.randomUUID());
        contact.setDisplayName(request.displayName().trim());
        contact.setStatus("active");
        contact.setCreatedBy(ownerId);
        contact.setCreatedAt(now);
        contact.setUpdatedAt(now);
        contact.setVersion(0L);
        contacts.insert(contact);

        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contact.getId());
        identity.setChannelType(channelType);
        identity.setIdentityScope(scope);
        identity.setIdentityValue(request.address().trim());
        identity.setNormalizedValue(normalized);
        identity.setDisplayName(request.displayName().trim());
        identity.setIsPrimary(true);
        identity.setVerifyStatus("unverified");
        identity.setSource("manual");
        identity.setCreatedAt(now);
        identity.setUpdatedAt(now);
        identity.setVersion(0L);
        if (identities.insertIfAbsent(identity) != 1) {
            contacts.deleteOwned(ownerId, contact.getId());
            throw new ChannelAddressBookException("CONTACT_IDENTITY_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }

        return new ChannelAddressBookItem(contact.getId(), identity.getId(), contact.getDisplayName(),
                null, channelType, identity.getIdentityValue(), identity.getDisplayName(), List.of(),
                "manual", null, false, true);
    }

    @Transactional
    public ResolvedContact resolveOrCreateInbound(UUID ownerId, String rawChannelType, UUID accountId,
                                                   String address, String displayName) {
        String channelType = normalizeChannelType(rawChannelType);
        String scope;
        if ("phone".equals(channelType)) {
            scope = ownerId.toString();
        } else {
            ChannelAccountEntity account = accounts.findByIdAndOwner(accountId, ownerId);
            if (account == null || !channelType.equals(normalizeChannelType(account.getChannelType()))
                    || "disabled".equals(account.getAuthStatus())) {
                throw new ChannelAddressBookException("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND);
            }
            scope = accountId.toString();
        }
        String normalized = normalizeAddress(channelType, address);
        var existing = identities.findByNormalizedValueInScope(channelType, scope, normalized);
        if (existing.isPresent()) {
            ContactIdentityEntity identity = existing.get();
            contacts.findByIdAndOwner(identity.getContactId(), ownerId).orElseThrow(() ->
                    new ChannelAddressBookException("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND));
            return new ResolvedContact(identity.getContactId(), identity.getId(), false);
        }

        String safeDisplayName = displayName == null || displayName.isBlank()
                ? address.trim() : displayName.trim();
        Instant now = Instant.now();
        ContactEntity contact = new ContactEntity();
        contact.setId(UUID.randomUUID());
        contact.setDisplayName(safeDisplayName);
        contact.setStatus("active");
        contact.setCreatedBy(ownerId);
        contact.setCreatedAt(now);
        contact.setUpdatedAt(now);
        contact.setVersion(0L);
        contacts.insert(contact);

        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contact.getId());
        identity.setChannelType(channelType);
        identity.setIdentityScope(scope);
        identity.setIdentityValue(address.trim());
        identity.setNormalizedValue(normalized);
        identity.setDisplayName(safeDisplayName);
        identity.setIsPrimary(true);
        identity.setVerifyStatus("unverified");
        identity.setSource("synced");
        identity.setCreatedAt(now);
        identity.setUpdatedAt(now);
        identity.setVersion(0L);
        if (identities.insertIfAbsent(identity) == 1) {
            return new ResolvedContact(contact.getId(), identity.getId(), true);
        }
        contacts.deleteOwned(ownerId, contact.getId());
        ContactIdentityEntity winner = identities.findByNormalizedValueInScope(
                channelType, scope, normalized).orElseThrow(() ->
                new ChannelAddressBookException("CONTACT_IDENTITY_CONFLICT", HttpStatus.CONFLICT));
        contacts.findByIdAndOwner(winner.getContactId(), ownerId).orElseThrow(() ->
                new ChannelAddressBookException("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND));
        return new ResolvedContact(winner.getContactId(), winner.getId(), false);
    }

    /**
     * Resolve a phone identity in the current user's private scope. An optional
     * contact id may be supplied by an existing upload; it is always checked for
     * ownership before a missing phone identity is attached to it.
     */
    @Transactional
    public ResolvedContact resolvePhone(UUID ownerId, UUID optionalContactId,
                                        String phoneNumber, String displayName) {
        if (ownerId == null) {
            throw new ChannelAddressBookException("AUTH_REQUIRED", HttpStatus.UNAUTHORIZED);
        }
        String normalized = normalizeAddress("phone", phoneNumber);
        String scope = ownerId.toString();

        if (optionalContactId != null) {
            contacts.findByIdAndOwner(optionalContactId, ownerId).orElseThrow(() ->
                    new ChannelAddressBookException("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND));
        }

        var existing = identities.findByNormalizedValueInScope("phone", scope, normalized);
        if (existing.isPresent()) {
            ContactIdentityEntity identity = existing.get();
            if (optionalContactId != null && !optionalContactId.equals(identity.getContactId())) {
                throw new ChannelAddressBookException("CONTACT_IDENTITY_CONFLICT", HttpStatus.CONFLICT);
            }
            contacts.findByIdAndOwner(identity.getContactId(), ownerId).orElseThrow(() ->
                    new ChannelAddressBookException("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND));
            return new ResolvedContact(identity.getContactId(), identity.getId(), false);
        }

        UUID contactId = optionalContactId;
        boolean createdContact = false;
        Instant now = Instant.now();
        String safeDisplayName = displayName == null || displayName.isBlank()
                ? normalized : displayName.trim();
        if (contactId == null) {
            ContactEntity contact = new ContactEntity();
            contactId = UUID.randomUUID();
            contact.setId(contactId);
            contact.setDisplayName(safeDisplayName);
            contact.setStatus("active");
            contact.setCreatedBy(ownerId);
            contact.setCreatedAt(now);
            contact.setUpdatedAt(now);
            contact.setVersion(0L);
            contacts.insert(contact);
            createdContact = true;
        }

        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contactId);
        identity.setChannelType("phone");
        identity.setIdentityScope(scope);
        identity.setIdentityValue(phoneNumber.trim());
        identity.setNormalizedValue(normalized);
        identity.setDisplayName(safeDisplayName);
        identity.setIsPrimary(true);
        identity.setVerifyStatus("unverified");
        identity.setSource("synced");
        identity.setCreatedAt(now);
        identity.setUpdatedAt(now);
        identity.setVersion(0L);
        if (identities.insertIfAbsent(identity) == 1) {
            return new ResolvedContact(contactId, identity.getId(), createdContact);
        }

        if (createdContact) {
            contacts.deleteOwned(ownerId, contactId);
        }
        ContactIdentityEntity winner = identities.findByNormalizedValueInScope(
                "phone", scope, normalized).orElseThrow(() ->
                new ChannelAddressBookException("CONTACT_IDENTITY_CONFLICT", HttpStatus.CONFLICT));
        contacts.findByIdAndOwner(winner.getContactId(), ownerId).orElseThrow(() ->
                new ChannelAddressBookException("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (optionalContactId != null && !optionalContactId.equals(winner.getContactId())) {
            throw new ChannelAddressBookException("CONTACT_IDENTITY_CONFLICT", HttpStatus.CONFLICT);
        }
        return new ResolvedContact(winner.getContactId(), winner.getId(), false);
    }

    @Transactional
    public void deleteManual(UUID ownerId, UUID contactId) {
        contacts.findByIdAndOwner(contactId, ownerId).orElseThrow(() ->
                new ChannelAddressBookException("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (identities.countActiveByContactId(contactId) == 0
                || identities.countNonManualByContactId(contactId) > 0) {
            throw new ChannelAddressBookException("CONTACT_NOT_MANUAL", HttpStatus.CONFLICT);
        }
        if (contacts.hasBusinessActivity(ownerId, contactId)) {
            throw new ChannelAddressBookException("CONTACT_HAS_ACTIVITY", HttpStatus.CONFLICT);
        }
        contacts.deleteTaggingsByContactId(contactId);
        identities.deleteByContactId(contactId);
        if (contacts.deleteOwned(ownerId, contactId) != 1) {
            throw new ChannelAddressBookException("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
    }

    private ChannelAddressBookItem toItem(ContactMapper.ChannelAddressBookRow row) {
        List<String> additional = row.additionalChannelTypes() == null
                || row.additionalChannelTypes().isBlank()
                ? List.of()
                : Arrays.stream(row.additionalChannelTypes().split(","))
                .filter(value -> !value.isBlank()).distinct().sorted().toList();
        return new ChannelAddressBookItem(row.contactId(), row.identityId(), row.displayName(),
                row.remark(), row.channelType(), row.address(), row.channelDisplayName(), additional,
                row.source(), row.lastContactAt(), row.hasActivity(), row.canDelete());
    }

    private String identityScope(UUID ownerId, String channelType) {
        if ("phone".equals(channelType)) return ownerId.toString();
        return accounts.findByOwnerAndChannelType(ownerId, channelType).stream()
                .filter(account -> "active".equals(account.getAuthStatus()))
                .map(ChannelAccountEntity::getId)
                .findFirst()
                .orElse(ownerId)
                .toString();
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) return null;
        String value = query.trim();
        if (value.length() > MAX_SEARCH_LENGTH) {
            throw new ChannelAddressBookException("CONTACT_SEARCH_TOO_LONG", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private static String normalizeChannelType(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("whatsapp".equals(value)) value = "chatapp";
        if (!List.of("chatapp", "email", "phone").contains(value)) {
            throw new ChannelAddressBookException("CHANNEL_TYPE_UNSUPPORTED", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private static String normalizeAddress(String channelType, String raw) {
        String value = raw == null ? "" : raw.trim();
        if ("email".equals(channelType)) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw new ChannelAddressBookException("CONTACT_ADDRESS_INVALID", HttpStatus.BAD_REQUEST);
            }
            return normalized;
        }
        String normalized = value.replaceAll("[^0-9]", "");
        if (normalized.length() < 6 || normalized.length() > 20) {
            throw new ChannelAddressBookException("CONTACT_ADDRESS_INVALID", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    public record ResolvedContact(UUID contactId, UUID identityId, boolean created) {}
}
