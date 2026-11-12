export function weComGroupDisplayName(value?: string | null): string {
  const displayName = value?.trim() ?? '';
  return !displayName || /^group:/i.test(displayName) ? '外部群聊' : displayName;
}
