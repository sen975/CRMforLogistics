import { useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Button, Flex, Modal, QRCode, Spin, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  createWeComAvatarAuthorization,
  fetchWeComAvatarAuthorization,
} from '../../api/endpoints';
import type {
  WeComAvatarAuthorizationAttempt,
  WeComAvatarAuthorizationProjection,
} from '../../api/types';

const { Text } = Typography;

interface WeComAvatarAuthorizationModalProps {
  open: boolean;
  onClose: () => void;
  onSucceeded: () => void | Promise<void>;
}

const isWeComBrowser = () => /\bwxwork\b/i.test(window.navigator.userAgent);

export function WeComAvatarAuthorizationModal({
  open,
  onClose,
  onSucceeded,
}: WeComAvatarAuthorizationModalProps) {
  const [attempt, setAttempt] = useState<WeComAvatarAuthorizationAttempt | null>(null);
  const requestedRef = useRef(false);
  const completedRef = useRef<string | null>(null);
  const createMutation = useMutation({
    mutationFn: createWeComAvatarAuthorization,
    onSuccess: (created) => {
      completedRef.current = null;
      setAttempt(created);
    },
  });
  const statusQuery = useQuery({
    queryKey: ['wecom-avatar-authorization', attempt?.authorizationId],
    queryFn: () => fetchWeComAvatarAuthorization(attempt!.authorizationId),
    enabled: open && Boolean(attempt?.authorizationId),
    retry: false,
    refetchInterval: (query) => (
      query.state.data?.status === 'PENDING' ? 2_000 : false
    ),
    refetchIntervalInBackground: false,
  });

  useEffect(() => {
    if (!open) {
      requestedRef.current = false;
      setAttempt(null);
      return;
    }
    if (requestedRef.current) return;
    requestedRef.current = true;
    createMutation.mutate();
  }, [open, createMutation.mutate]);

  const projection: WeComAvatarAuthorizationProjection | WeComAvatarAuthorizationAttempt | null =
    statusQuery.data ?? attempt;

  useEffect(() => {
    if (!open || projection?.status !== 'SUCCEEDED'
        || completedRef.current === projection.authorizationId) return;
    completedRef.current = projection.authorizationId;
    void onSucceeded();
  }, [open, onSucceeded, projection?.authorizationId, projection?.status]);

  const retry = () => {
    setAttempt(null);
    completedRef.current = null;
    createMutation.reset();
    createMutation.mutate();
  };

  const content = () => {
    if (createMutation.isPending || !attempt) {
      return <Flex justify="center" style={{ padding: 32 }}><Spin /></Flex>;
    }
    if (createMutation.isError || statusQuery.isError) {
      return (
        <Flex vertical gap={12}>
          <Alert type="error" showIcon message="无法读取头像授权状态" />
          <Button onClick={retry}>重新生成授权二维码</Button>
        </Flex>
      );
    }
    if (projection?.status === 'FAILED' || projection?.status === 'EXPIRED') {
      return (
        <Flex vertical gap={12}>
          <Alert
            type="warning"
            showIcon
            message={projection.status === 'EXPIRED'
              ? '头像授权已过期，请重新发起' : '头像授权失败，请重新发起'}
          />
          <Button onClick={retry}>重新生成授权二维码</Button>
        </Flex>
      );
    }
    if (projection?.status === 'SUCCEEDED') {
      return <Alert type="success" showIcon message="企业微信头像授权成功" />;
    }
    return (
      <Flex vertical align="center" gap={16}>
        {isWeComBrowser() ? (
          <Button type="primary" href={attempt.authorizationUrl}>在企业微信中授权</Button>
        ) : (
          <div aria-label="企业微信头像授权二维码">
            <QRCode value={attempt.authorizationUrl} size={200} type="svg" bordered={false} />
          </div>
        )}
        <Text type="secondary">等待授权完成</Text>
      </Flex>
    );
  };

  return (
    <Modal
      title="授权企业微信头像"
      open={open}
      onCancel={onClose}
      footer={<Button onClick={onClose}>关闭</Button>}
      width={360}
      destroyOnHidden
    >
      {content()}
    </Modal>
  );
}
