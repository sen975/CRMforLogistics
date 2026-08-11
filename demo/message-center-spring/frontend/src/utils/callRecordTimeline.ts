export const CALL_RECORD_POLL_INTERVAL_MS = 2000;

export function callRecordPlacement(direction: string): 'left' | 'right' {
  return direction === 'outbound' ? 'right' : 'left';
}

export function hasActiveCallRecord(records: ReadonlyArray<{ state: string }>): boolean {
  return records.some((record) => record.state === 'queued' || record.state === 'processing');
}
