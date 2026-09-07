import { useEffect, useState } from 'react';
import { Avatar } from 'antd';
import { fetchAccountAvatarBlob } from '../api/endpoints';
import type { AccountProfile } from '../api/types';

export function AccountAvatar({ avatar, size = 48 }: {
  avatar: AccountProfile['avatar'];
  size?: number;
}) {
  const [source, setSource] = useState<string | null>(
    avatar.source === 'WECOM' ? avatar.contentUrl : null,
  );
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    setFailed(false);
    if (avatar.source === 'WECOM') {
      setSource(avatar.contentUrl);
      return undefined;
    }
    if (avatar.source !== 'UPLOAD') {
      setSource(null);
      return undefined;
    }
    let active = true;
    let objectUrl: string | null = null;
    void fetchAccountAvatarBlob()
      .then((blob) => {
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setSource(objectUrl);
      })
      .catch(() => {
        if (active) setFailed(true);
      });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [avatar.contentUrl, avatar.revision, avatar.source]);

  return (
    <Avatar
      size={size}
      src={!failed && source ? source : undefined}
      onError={() => { setFailed(true); return false; }}
      style={{ background: '#1677ff', flex: '0 0 auto' }}
    >
      {avatar.initial}
    </Avatar>
  );
}
