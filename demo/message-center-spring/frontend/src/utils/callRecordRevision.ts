interface TranscriptRevisionSnapshot {
  currentRevisionId: string | null;
  version: number;
}

export type TranscriptSaveDecision =
  | { kind: 'save'; expectedVersion: number }
  | { kind: 'conflict' };

export function decideTranscriptSave(
  baseRevisionId: string | null,
  latest: TranscriptRevisionSnapshot,
): TranscriptSaveDecision {
  if (latest.currentRevisionId !== baseRevisionId) {
    return { kind: 'conflict' };
  }
  return { kind: 'save', expectedVersion: latest.version };
}
