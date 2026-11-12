package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.callrecord.CallRecordContactBackfillService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/call-records")
public class AdminCallRecordBackfillController {
    private final CallRecordContactBackfillService backfill;

    public AdminCallRecordBackfillController(CallRecordContactBackfillService backfill) {
        this.backfill = backfill;
    }

    @PostMapping("/contact-backfill")
    public CallRecordContactBackfillService.BackfillResult backfill(
            @RequestParam(defaultValue = "200") int limit) {
        return backfill.run(limit);
    }
}
