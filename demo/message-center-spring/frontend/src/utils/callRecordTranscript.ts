import type { CallRecordResponse } from '../api/types';

const HARD_BREAKS = new Set(['。', '！', '？', '；', '!', '?', ';']);

export function canRetryTranscription(record: CallRecordResponse): boolean {
  if (record.transcription.state === 'failed') return true;
  return record.transcription.state === 'completed'
    && (record.transcription.result?.segments.length ?? 0) === 0;
}

export function splitTranscriptDisplayLines(text: string): string[] {
  if (text.length === 0) return [''];
  const lines: string[] = [];
  let lineStart = 0;
  for (let index = 0; index < text.length; index += 1) {
    const current = text[index];
    const previous = text[index - 1] ?? '';
    const next = text[index + 1] ?? '';
    const decimalPoint = current === '.' && /\d/.test(previous) && /\d/.test(next);
    const englishPeriod = current === '.'
      && !decimalPoint
      && next !== '.'
      && (next === '' || /\s/.test(next));
    if (HARD_BREAKS.has(current) || englishPeriod) {
      lines.push(text.slice(lineStart, index + 1));
      lineStart = index + 1;
    }
  }
  if (lineStart < text.length) lines.push(text.slice(lineStart));
  return lines.length > 0 ? lines : [text];
}
