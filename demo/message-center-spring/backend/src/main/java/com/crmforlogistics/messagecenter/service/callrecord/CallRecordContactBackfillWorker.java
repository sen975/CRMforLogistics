package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.service.contact.ChannelAddressBookService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CallRecordContactBackfillWorker {
    private final CallRecordMapper records;
    private final ChannelAddressBookService addressBooks;

    public CallRecordContactBackfillWorker(CallRecordMapper records,
                                           ChannelAddressBookService addressBooks) {
        this.records = records;
        this.addressBooks = addressBooks;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean process(CallRecordEntity record, UUID owner, String normalizedPhone) {
        ChannelAddressBookService.ResolvedContact resolved = addressBooks.resolvePhone(
                owner, record.getContactId(), normalizedPhone, null);
        String anchor = "phone:" + normalizedPhone;
        if (records.updateContactBinding(record.getId(), owner, resolved.contactId(),
                anchor, record.getVersion()) == 0) {
            throw new IllegalStateException("CALL_RECORD_VERSION_CONFLICT");
        }
        return resolved.created();
    }
}
