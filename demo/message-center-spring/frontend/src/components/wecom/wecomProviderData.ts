import type { WeComProviderData } from '../../api/types';

export interface WeComDirectoryMember {
  userId: string;
  name: string;
  avatar?: string;
  department?: number[];
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
    : [];
}

export function directoryMembers(data: WeComProviderData | undefined): WeComDirectoryMember[] {
  const raw = Array.isArray(data?.userlist)
    ? data.userlist
    : Array.isArray(data?.user) ? data.user : [];
  return raw.flatMap((item) => {
    if (!item || typeof item !== 'object') return [];
    const record = item as Record<string, unknown>;
    const userId = typeof record.userid === 'string' ? record.userid.trim() : '';
    if (!userId) return [];
    const name = typeof record.name === 'string' && record.name.trim() ? record.name.trim() : userId;
    const avatar = typeof record.avatar === 'string' && record.avatar.trim() ? record.avatar.trim() : undefined;
    const department = Array.isArray(record.department)
      ? record.department.filter((value): value is number => typeof value === 'number')
      : undefined;
    return [{ userId, name, avatar, department }];
  });
}

export interface WeComDepartment {
  id: number;
  name: string;
  parentId?: number;
  order?: number;
}

export function directoryDepartments(data: WeComProviderData | undefined): WeComDepartment[] {
  const raw = Array.isArray(data?.department)
    ? data.department
    : Array.isArray(data?.departments) ? data.departments : [];
  return raw.flatMap((item) => {
    if (!item || typeof item !== 'object') return [];
    const record = item as Record<string, unknown>;
    const id = typeof record.id === 'number' ? record.id : Number(record.id);
    if (!Number.isSafeInteger(id) || id <= 0) return [];
    const name = typeof record.name === 'string' && record.name.trim() ? record.name.trim() : `部门 ${id}`;
    const parentId = typeof record.parentid === 'number' ? record.parentid : undefined;
    const order = typeof record.order === 'number' ? record.order : undefined;
    return [{ id, name, parentId, order }];
  });
}

export function externalContactIds(data: WeComProviderData | undefined): string[] {
  return stringArray(data?.external_userid);
}

export function customerGroupIds(data: WeComProviderData | undefined): string[] {
  return stringArray(data?.group_chat_id_list);
}
