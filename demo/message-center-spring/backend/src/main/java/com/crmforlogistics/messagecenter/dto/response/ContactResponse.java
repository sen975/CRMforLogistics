package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contact list/item response DTO modeled after the old frontend's expectations.
 *
 * <p>The old SPA (App.java pageHtml) renders these fields from the /api/contacts JSON:
 * <ul>
 *   <li>{@code id} -- contact UUID, used as the selection key</li>
 *   <li>{@code displayName} -- rendered in .contact-name</li>
 *   <li>{@code channels} -- primary channel drives the avatar icon; the old frontend
 *       iterates channels to populate channel tabs</li>
 *   <li>{@code lastText} -- shown in .contact-last</li>
 *   <li>{@code messageCount} -- used for unread delta computation in reconcileUnreadContacts</li>
 * </ul>
 *
 * <p>{@code channelTypes} is derived from {@code contact_identities.channel_type} and
 * {@code lastMessageAt} from the most recent conversation for this contact.
 * {@code unreadCount} counts messages with {@code counts_as_unread = true} across
 * all conversations belonging to this contact.
 */
public record ContactResponse(
        UUID id,
        String displayName,
        String remark,
        List<String> channelTypes,
        Instant lastMessageAt,
        String lastText,
        int messageCount,
        int unreadCount,
        List<ContactTagResponse> tags,
        List<ContactIdentityResponse> identities
) {}
