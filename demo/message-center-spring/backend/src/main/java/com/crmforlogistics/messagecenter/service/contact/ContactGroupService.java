package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.dto.request.ContactTagsRequest;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicOwnerActivityService;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicOwnerService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Service for contact-group operations: merge, split, remark, and profile updates.
 *
 * <p>Ported from the old project's {@code UnifiedMessageStore} (file-backed) and
 * {@code JdbcContactRepository} (database-backed) merge/split/profile logic.
 */
@Service
public class ContactGroupService {

    private final ContactMapper contactMapper;
    private final ContactIdentityMapper contactIdentityMapper;
    private final ContactTagMapper contactTagMapper;
    private final AiTopicMapper aiTopicMapper;
    private final AiTopicOwnerActivityService aiTopicActivities;

    public ContactGroupService(ContactMapper contactMapper,
                               ContactIdentityMapper contactIdentityMapper) {
        this(contactMapper, contactIdentityMapper, null, null, null);
    }

    public ContactGroupService(ContactMapper contactMapper,
                               ContactIdentityMapper contactIdentityMapper,
                               ContactTagMapper contactTagMapper) {
        this(contactMapper, contactIdentityMapper, contactTagMapper, null, null);
    }

    public ContactGroupService(ContactMapper contactMapper,
                               ContactIdentityMapper contactIdentityMapper,
                               AiTopicMapper aiTopicMapper,
                               AiTopicOwnerActivityService aiTopicActivities) {
        this(contactMapper, contactIdentityMapper, null, aiTopicMapper, aiTopicActivities);
    }

    @Autowired
    public ContactGroupService(ContactMapper contactMapper,
                               ContactIdentityMapper contactIdentityMapper,
                               ContactTagMapper contactTagMapper,
                               AiTopicMapper aiTopicMapper,
                               AiTopicOwnerActivityService aiTopicActivities) {
        this.contactMapper = contactMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.contactTagMapper = contactTagMapper;
        this.aiTopicMapper = aiTopicMapper;
        this.aiTopicActivities = aiTopicActivities;
    }

    /**
     * Merge all identities from {@code sourceContactId} into {@code targetContactId},
     * then mark the source contact as merged.
     *
     * @param sourceContactId the contact whose identities will be moved
     * @param targetContactId the contact that will absorb the identities
     * @param userId          the actor performing the merge
     * @throws IllegalArgumentException if the two contact ids are equal or either is null
     */
    @Transactional
    public void merge(UUID sourceContactId, UUID targetContactId, UUID userId) {
        Objects.requireNonNull(sourceContactId, "sourceContactId");
        Objects.requireNonNull(targetContactId, "targetContactId");
        if (Objects.equals(sourceContactId, targetContactId)) {
            throw new IllegalArgumentException("Contacts must differ");
        }

        // Move all identities from source to target
        contactIdentityMapper.updateContactId(targetContactId, sourceContactId);

        // Mark source contact as merged
        ContactEntity source = contactMapper.selectById(sourceContactId);
        if (source == null) {
            throw new IllegalArgumentException("Source contact not found: " + sourceContactId);
        }
        if (aiTopicMapper != null) {
            aiTopicMapper.archiveReadyByContact(sourceContactId);
        }
        source.setStatus("merged");
        source.setMergedToId(targetContactId);
        source.setUpdatedAt(Instant.now());
        contactMapper.updateById(source);
        if (aiTopicActivities != null) {
            aiTopicActivities.recordActivity(AiTopicOwnerService.contact(targetContactId), Instant.now());
        }
    }

    /**
     * Create a new contact from a single identity, moving that identity to the
     * newly created contact.
     *
     * @param identityId      the identity to split out
     * @param newContactName  display name for the new contact
     * @param userId          the actor performing the split
     * @return the UUID of the newly created contact
     * @throws IllegalArgumentException if the identity is not found
     */
    @Transactional
    public UUID split(UUID identityId, String newContactName, UUID userId) {
        Objects.requireNonNull(identityId, "identityId");
        String name = requireText(newContactName, "newContactName", 100);

        // Create new contact
        ContactEntity newContact = new ContactEntity();
        newContact.setId(UUID.randomUUID());
        newContact.setDisplayName(name);
        newContact.setStatus("active");
        if (userId != null) {
            newContact.setCreatedBy(userId);
        }
        newContact.setCreatedAt(Instant.now());
        newContact.setUpdatedAt(Instant.now());
        contactMapper.insert(newContact);

        // Move the single identity to the new contact
        int updated = contactIdentityMapper.updateContactIdForIdentity(
                newContact.getId(), identityId);
        if (updated == 0) {
            throw new IllegalArgumentException(
                    "Contact identity not found or already moved: " + identityId);
        }

        return newContact.getId();
    }

    /**
     * Update the remark on a contact.
     *
     * @param contactId the contact UUID
     * @param remark    the new remark text (may be null or blank to clear)
     * @param userId    the actor performing the update
     * @throws IllegalArgumentException if the contact is not found
     */
    public void updateRemark(UUID contactId, String remark, UUID userId) {
        Objects.requireNonNull(contactId, "contactId");
        ContactEntity contact = requireContact(contactId);
        contact.setRemark(remark == null || remark.isBlank() ? null : remark.trim());
        contact.setUpdatedAt(Instant.now());
        contactMapper.updateById(contact);
    }

    /** Replace the CRM tags assigned to a contact, within the caller's access scope. */
    @Transactional
    public void updateTags(UUID contactId, List<ContactTagsRequest.ContactTagInput> inputs,
                           UUID userId) {
        Objects.requireNonNull(contactId, "contactId");
        if (contactTagMapper == null) {
            throw new IllegalStateException("Contact tag support is unavailable");
        }
        contactMapper.findAccessibleById(contactId, userId, ContactService.isCurrentUserAdmin())
                .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + contactId));

        LinkedHashMap<String, ContactTagsRequest.ContactTagInput> unique = new LinkedHashMap<>();
        List<ContactTagsRequest.ContactTagInput> normalizedInputs = inputs == null
                ? List.of() : inputs;
        for (ContactTagsRequest.ContactTagInput input : normalizedInputs) {
            if (input == null || input.name() == null || input.name().isBlank()) {
                continue;
            }
            String name = input.name().trim();
            requireLength(name, "tag name", 100);
            String color = input.color() == null || input.color().isBlank()
                    ? null : input.color().trim();
            if (color != null) requireLength(color, "tag color", 30);
            unique.putIfAbsent(name.toLowerCase(Locale.ROOT),
                    new ContactTagsRequest.ContactTagInput(name, color));
        }

        contactTagMapper.deleteByContactId(contactId);
        for (ContactTagsRequest.ContactTagInput input : unique.values()) {
            contactTagMapper.insertTag(input.name(), input.color());
            ContactTagMapper tagMapper = contactTagMapper;
            var tag = tagMapper.findActiveByName(input.name())
                    .orElseThrow(() -> new IllegalStateException("Contact tag was not created"));
            tagMapper.insertTagging(contactId, tag.id());
        }
    }

    /**
     * Update profile fields (display name, role title) on a contact.
     *
     * @param contactId   the contact UUID
     * @param displayName the new display name (may be null to leave unchanged)
     * @param roleTitle   the new role title (may be null to leave unchanged)
     * @param userId      the actor performing the update
     * @throws IllegalArgumentException if the contact is not found
     */
    public void updateProfile(UUID contactId, String displayName,
                               String roleTitle, UUID userId) {
        Objects.requireNonNull(contactId, "contactId");
        ContactEntity contact = requireContact(contactId);

        if (displayName != null && !displayName.isBlank()) {
            String cleaned = displayName.trim();
            requireLength(cleaned, "displayName", 100);
            contact.setDisplayName(cleaned);
        }
        if (roleTitle != null) {
            String cleaned = roleTitle.trim();
            if (!cleaned.isBlank()) {
                requireLength(cleaned, "roleTitle", 100);
            }
            contact.setRoleTitle(cleaned.isBlank() ? null : cleaned);
        }

        contact.setUpdatedAt(Instant.now());
        contactMapper.updateById(contact);
    }

    private ContactEntity requireContact(UUID contactId) {
        ContactEntity contact = contactMapper.selectById(contactId);
        if (contact == null) {
            throw new IllegalArgumentException("Contact not found: " + contactId);
        }
        return contact;
    }

    private static String requireText(String value, String fieldName, int maxLength) {
        Objects.requireNonNull(value, fieldName);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        requireLength(trimmed, fieldName, maxLength);
        return trimmed;
    }

    private static void requireLength(String value, String fieldName, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    fieldName + " must be <= " + maxLength + " characters");
        }
    }
}
