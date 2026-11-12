export type WeComAvatarKind = 'employee' | 'external' | 'robot' | 'unknown';

const AVATAR_COLORS: Record<WeComAvatarKind, string> = {
  employee: '#1677ff',
  external: '#13a8a8',
  robot: '#fa8c16',
  unknown: '#8c8c8c',
};

export function wecomAvatarKind(partyType?: string | null): WeComAvatarKind {
  if (partyType === 'EMPLOYEE') return 'employee';
  if (partyType === 'EXTERNAL_CONTACT') return 'external';
  if (partyType === 'ROBOT') return 'robot';
  return 'unknown';
}

export function wecomAvatarColor(partyType?: string | null): string {
  return AVATAR_COLORS[wecomAvatarKind(partyType)];
}

export function wecomPartyTypeLabel(partyType?: string | null): string {
  if (partyType === 'EMPLOYEE') return '员工';
  if (partyType === 'EXTERNAL_CONTACT') return '客户';
  if (partyType === 'ROBOT') return '机器人';
  return '成员';
}

export function wecomAvatarLetter(displayName?: string | null): string {
  const value = displayName?.trim();
  return value ? Array.from(value)[0] : '未';
}
