package com.crmforlogistics.messagecenter.dto.request;

import java.util.UUID;

/**
 * Multipurpose request DTO for contact-group and contact-profile operations.
 *
 * <p>Different endpoints use different subsets of fields:
 * <ul>
 *   <li><b>Merge</b>: {@code sourceContactId}, {@code targetContactId}</li>
 *   <li><b>Split</b>: {@code identityId}, {@code newContactName}</li>
 *   <li><b>Remark</b>: {@code remark}</li>
 *   <li><b>Profile</b>: {@code displayName}, {@code roleTitle}</li>
 * </ul>
 */
public record ContactGroupRequest(
        // merge
        UUID sourceContactId,
        UUID targetContactId,
        // split
        UUID identityId,
        String newContactName,
        // remark & profile
        String remark,
        String displayName,
        String roleTitle
) {}
