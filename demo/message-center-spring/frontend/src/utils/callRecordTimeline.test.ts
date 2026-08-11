import { describe, expect, it } from 'vitest';
import {
  callRecordPlacement,
  hasActiveCallRecord,
  CALL_RECORD_POLL_INTERVAL_MS,
} from './callRecordTimeline';

describe('call record timeline rules', () => {
  it('places outbound calls on the right and inbound calls on the left', () => {
    expect(callRecordPlacement('outbound')).toBe('right');
    expect(callRecordPlacement('inbound')).toBe('left');
  });

  it('polls only while a call is queued or processing', () => {
    expect(hasActiveCallRecord([{ state: 'completed' }, { state: 'failed' }])).toBe(false);
    expect(hasActiveCallRecord([{ state: 'queued' }])).toBe(true);
    expect(hasActiveCallRecord([{ state: 'processing' }])).toBe(true);
    expect(CALL_RECORD_POLL_INTERVAL_MS).toBe(2000);
  });
});
