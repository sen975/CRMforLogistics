import { WechatOutlined } from '@ant-design/icons';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Button, Descriptions, Flex, Popconfirm, Spin, Tag, Typography } from 'antd';
import { useState } from 'react';
import {
  exchangeWeComBinding,
  fetchWeComBinding,
  unbindWeCom,
} from '../../api/endpoints';
import { WeComLoginPanel } from './WeComLoginPanel';
import { WeComAvatarAuthorizationModal } from './WeComAvatarAuthorizationModal';
import { useAuth } from '../../hooks/useAuth';

const { Text } = Typography;

function errorMessage(error: unknown): string {
  if (error && typeof error === 'object' && 'response' in error) {
    const response = (error as { response?: { data?: { message?: unknown } } }).response;
    if (typeof response?.data?.message === 'string' && response.data.message.trim()) {
      return response.data.message;
    }
  }
  return '企业微信绑定操作失败，请重试';
}

export function WeComBindingPanel() {
  const { profile, refreshProfile } = useAuth();
  const [binding, setBinding] = useState(false);
  const [avatarAuthorizationOpen, setAvatarAuthorizationOpen] = useState(false);
  const [operationError, setOperationError] = useState<string | null>(null);
  const bindingQuery = useQuery({
    queryKey: ['wecom-binding'],
    queryFn: fetchWeComBinding,
  });
  const exchangeMutation = useMutation({
    mutationFn: exchangeWeComBinding,
  });
  const unbindMutation = useMutation({ mutationFn: unbindWeCom });

  if (bindingQuery.isPending) {
    return <Flex justify="center" style={{ padding: 24 }}><Spin size="small" /></Flex>;
  }

  if (bindingQuery.isError) {
    return (
      <Alert
        type="error"
        showIcon
        message="无法读取企业微信绑定状态"
        action={<Button type="text" onClick={() => void bindingQuery.refetch()}>重试</Button>}
      />
    );
  }

  const current = bindingQuery.data;

  if (current.bound) {
    const unbind = async () => {
      setOperationError(null);
      try {
        await unbindMutation.mutateAsync();
        await bindingQuery.refetch();
      } catch (error) {
        setOperationError(errorMessage(error));
      }
    };
    return (
      <Flex vertical gap={16}>
        <Descriptions size="small" column={1} colon={false}>
          <Descriptions.Item label="企业微信">
            <Tag color="success">已绑定企业微信</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="企业微信昵称">{current.wecomDisplayName?.trim() || '未获取'}</Descriptions.Item>
          <Descriptions.Item label="所属企业">{current.corpName?.trim() || '未获取'}</Descriptions.Item>
        </Descriptions>
        {operationError && <Alert type="error" showIcon message={operationError} />}
        {profile?.avatar.source !== 'WECOM' && (
          <Button
            icon={<WechatOutlined />}
            onClick={() => setAvatarAuthorizationOpen(true)}
          >
            授权企业微信头像
          </Button>
        )}
        <Popconfirm
          title="解除企业微信绑定"
          description="解除后仍可使用账号密码登录。"
          okText="解除绑定"
          cancelText="取消"
          onConfirm={() => void unbind()}
        >
          <Button danger loading={unbindMutation.isPending}>解除绑定</Button>
        </Popconfirm>
        <WeComAvatarAuthorizationModal
          open={avatarAuthorizationOpen}
          onClose={() => setAvatarAuthorizationOpen(false)}
          onSucceeded={async () => {
            await bindingQuery.refetch();
            await refreshProfile();
            setAvatarAuthorizationOpen(false);
          }}
        />
      </Flex>
    );
  }

  if (!binding) {
    return (
      <Flex vertical gap={12} align="flex-start">
        <Text type="secondary">绑定后可使用企业微信扫码登录。</Text>
        <Button
          aria-label="绑定企业微信"
          icon={<WechatOutlined />}
          onClick={() => setBinding(true)}
        >
          绑定企业微信
        </Button>
      </Flex>
    );
  }

  const exchange = async (code: string, state: string) => {
    setOperationError(null);
      try {
        await exchangeMutation.mutateAsync({ code, state });
        await bindingQuery.refetch();
        await refreshProfile();
        setBinding(false);
    } catch (error) {
      setOperationError(errorMessage(error));
    }
  };

  return (
    <Flex vertical gap={12}>
      {operationError && <Alert type="error" showIcon message={operationError} />}
      <WeComLoginPanel purpose="bind" onAuthenticated={exchange} />
      <Button type="text" onClick={() => setBinding(false)}>取消</Button>
    </Flex>
  );
}
