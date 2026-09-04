import { useEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Layout, Button, Drawer, Typography, Space, Tooltip, Dropdown, theme, type MenuProps } from 'antd';
import {
  ContactsOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  SendOutlined,
  FileTextOutlined,
  SettingOutlined,
  PhoneOutlined,
  NotificationOutlined,
  UserOutlined,
  WechatOutlined,
  FileSearchOutlined,
  WhatsAppOutlined,
  MailOutlined,
} from '@ant-design/icons';
import { useAuth } from '../hooks/useAuth';
import { DetailPanelProvider, useDetailPanel } from '../hooks/useDetailPanel';
import ContactsPage from '../pages/ContactsPage';
import ContactDetailPanel from './ContactDetailPanel';
import CallRecordDetail from './CallRecordDetail';
import WeComGroupDetailPanel from './WeComGroupDetailPanel';
import { AccountPanel } from './AccountPanel';
import { AccountAvatar } from './AccountAvatar';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

function AppLayoutInner() {
  const { username, profile, logout, isAdmin, canBroadcast } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const {
    selectedDetail,
    clearSelection,
    detailPanelOpen,
    toggleDetailPanel,
    setDetailPanelOpen,
  } = useDetailPanel();
  const { token } = theme.useToken();
  const [isMobile, setIsMobile] = useState(
    () => window.matchMedia('(max-width: 767px)').matches,
  );
  const [contactsOpen, setContactsOpen] = useState(false);
  const [accountOpen, setAccountOpen] = useState(false);

  const isThreadPage = location.pathname.startsWith('/thread/') || location.pathname.startsWith('/conversations/contact/');
  const isWeComGroupPage = location.pathname.startsWith('/conversations/wecom-group/');
  const isPhoneRepositoryPage = location.pathname === '/phone-repository';
  const supportsDetailPanel = isThreadPage || isWeComGroupPage || isPhoneRepositoryPage;
  const detailScope = isThreadPage ? 'thread' : isWeComGroupPage ? 'wecom-group' : isPhoneRepositoryPage ? 'phone-repository' : 'none';
  const selectedCallRecordId = selectedDetail?.kind === 'callRecord' ? selectedDetail.id : null;
  const detailPanelTitle = selectedCallRecordId ? '电话详情' : isWeComGroupPage ? '群 Topic' : '联系人详情';

  useEffect(() => {
    clearSelection();
    setDetailPanelOpen(false);
  }, [detailScope, clearSelection, setDetailPanelOpen]);

  useEffect(() => {
    const mediaQuery = window.matchMedia('(max-width: 767px)');
    const handleChange = (event: MediaQueryListEvent) => setIsMobile(event.matches);
    setIsMobile(mediaQuery.matches);
    mediaQuery.addEventListener('change', handleChange);
    return () => mediaQuery.removeEventListener('change', handleChange);
  }, []);

  useEffect(() => {
    setContactsOpen(false);
  }, [location.pathname]);

  const navigationButton = (
    label: string,
    icon: React.ReactNode,
    onClick: () => void,
  ) => {
    const button = (
      <Button
        aria-label={isMobile ? label : undefined}
        icon={icon}
        onClick={onClick}
      >
        {isMobile ? null : label}
      </Button>
    );
    return isMobile ? <Tooltip title={label}>{button}</Tooltip> : button;
  };

  const navigationDropdown = (
    label: string,
    icon: React.ReactNode,
    items: MenuProps['items'],
  ) => {
    const button = (
      <Button
        aria-label={label}
        aria-haspopup="menu"
        icon={icon}
      >
        {isMobile ? null : label}
      </Button>
    );
    return (
      <Dropdown
        menu={{ items }}
        trigger={isMobile ? ['click'] : ['hover', 'click']}
      >
        {isMobile ? <Tooltip title={label}>{button}</Tooltip> : button}
      </Dropdown>
    );
  };

  const whatsappItems: MenuProps['items'] = [
    {
      key: 'chatapp-address-book',
      icon: <ContactsOutlined aria-hidden="true" />,
      label: '通讯录',
      onClick: () => navigate('/address-book/chatapp'),
    },
    {
      key: 'templates',
      icon: <FileTextOutlined aria-hidden="true" />,
      label: '模板',
      onClick: () => navigate('/templates'),
    },
    canBroadcast
      ? {
          key: 'broadcasts',
          icon: <NotificationOutlined aria-hidden="true" />,
          label: '群发',
          onClick: () => navigate('/broadcasts'),
        }
      : null,
  ].filter((item): item is NonNullable<typeof item> => item !== null);

  const phoneItems: MenuProps['items'] = [
    {
      key: 'phone-address-book',
      icon: <ContactsOutlined aria-hidden="true" />,
      label: '通讯录',
      onClick: () => navigate('/address-book/phone'),
    },
    {
      key: 'phone-repository',
      icon: <PhoneOutlined aria-hidden="true" />,
      label: '电话仓库',
      onClick: () => navigate('/phone-repository'),
    },
  ];

  const systemItems: MenuProps['items'] = [
    {
      key: 'send',
      icon: <SendOutlined aria-hidden="true" />,
      label: '发送',
      onClick: () => navigate('/send'),
    },
    {
      key: 'topic-repository',
      icon: <FileSearchOutlined aria-hidden="true" />,
      label: 'Topic 仓库',
      onClick: () => navigate('/topic-repository'),
    },
    {
          key: 'channel-settings',
          icon: <SettingOutlined aria-hidden="true" />,
          label: '渠道设置',
          onClick: () => navigate('/settings/channels'),
        },
    isAdmin
      ? {
          key: 'users',
          icon: <UserOutlined aria-hidden="true" />,
          label: '用户管理',
          onClick: () => navigate('/settings/users'),
        }
      : null,
    { type: 'divider' as const },
    {
      key: 'account',
      icon: <UserOutlined aria-hidden="true" />,
      label: '账号',
      onClick: () => setAccountOpen(true),
    },
    {
      key: 'logout',
      icon: <LogoutOutlined aria-hidden="true" />,
      label: '退出登录',
      onClick: logout,
    },
  ].filter((item): item is NonNullable<typeof item> => item !== null);

  return (
    <Layout style={{ height: '100vh' }}>
      <Header
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0 16px',
          background: token.colorBgContainer,
          borderBottom: `1px solid ${token.colorBorderSecondary}`,
        }}
      >
        <Space size={isMobile ? 4 : 8}>
          {isMobile && (
            <Tooltip title="联系人">
              <Button
                aria-label="联系人"
                icon={<ContactsOutlined />}
                onClick={() => setContactsOpen(true)}
              />
            </Tooltip>
          )}
          <Text strong style={{ fontSize: 16, whiteSpace: 'nowrap' }}>
            {isMobile ? '消息中心' : '统一消息中心'}
          </Text>
        </Space>
        <Space size={isMobile ? 4 : 8}>
          {navigationDropdown('WhatsApp', <WhatsAppOutlined />, whatsappItems)}
          {navigationDropdown('邮件', <MailOutlined />, [{ key: 'email-address-book', icon: <ContactsOutlined aria-hidden="true" />, label: '通讯录', onClick: () => navigate('/address-book/email') }])}
          {isAdmin && navigationButton('企业微信', <WechatOutlined />, () => navigate('/settings/wecom'))}
          {navigationDropdown('电话', <PhoneOutlined />, phoneItems)}
          {navigationDropdown('系统设置', <SettingOutlined />, systemItems)}
          {supportsDetailPanel && (
            <Button
              aria-label={detailPanelOpen ? '收起右侧栏' : '展开右侧栏'}
              icon={detailPanelOpen ? <MenuFoldOutlined /> : <MenuUnfoldOutlined />}
              onClick={toggleDetailPanel}
            />
          )}
          {!isMobile && profile ? <Space size={6}>
            <AccountAvatar avatar={profile.avatar} size={28} />
            <Text type="secondary">{profile.displayName}</Text>
          </Space> : !isMobile ? <Text type="secondary">{username}</Text> : null}
        </Space>
      </Header>
      <Layout style={{ flex: 1, overflow: 'hidden' }}>
        {!isMobile && (
          <Sider
            width={320}
            style={{
              background: token.colorBgContainer,
              borderRight: `1px solid ${token.colorBorderSecondary}`,
              overflow: 'hidden',
            }}
          >
            <ContactsPage />
          </Sider>
        )}
        <Content
          style={{
            overflow: 'auto',
            padding: isMobile ? 0 : 16,
            minWidth: 0,
            background: token.colorBgContainer,
          }}
        >
          <Outlet />
        </Content>
        {supportsDetailPanel && !isMobile && (
          <Sider
            width={selectedCallRecordId ? 560 : 360}
            collapsedWidth={0}
            collapsed={!detailPanelOpen}
            trigger={null}
            style={{
              background: token.colorBgContainer,
              borderLeft: `1px solid ${token.colorBorderSecondary}`,
              overflow: 'auto',
              transition: 'all 0.24s ease',
            }}
          >
            {selectedCallRecordId
              ? <CallRecordDetail callRecordId={selectedCallRecordId} />
              : isWeComGroupPage
                ? <WeComGroupDetailPanel />
                : <ContactDetailPanel />}
          </Sider>
        )}
      </Layout>
      <Drawer
        title="联系人"
        placement="left"
        width="min(320px, 100vw)"
        open={isMobile && contactsOpen}
        onClose={() => setContactsOpen(false)}
        styles={{ body: { padding: 0 } }}
      >
        <ContactsPage />
      </Drawer>
      <Drawer
        title="账号"
        width="min(420px, 100vw)"
        open={accountOpen}
        onClose={() => setAccountOpen(false)}
      >
        <AccountPanel />
      </Drawer>
      <Drawer
        title={detailPanelTitle}
        width="min(560px, 100vw)"
        open={isMobile && supportsDetailPanel && detailPanelOpen}
        onClose={() => setDetailPanelOpen(false)}
        styles={{ body: { padding: 0 } }}
      >
        {selectedCallRecordId
          ? <CallRecordDetail callRecordId={selectedCallRecordId} />
          : isWeComGroupPage
            ? <WeComGroupDetailPanel />
            : <ContactDetailPanel />}
      </Drawer>
    </Layout>
  );
}

export default function AppLayout() {
  return (
    <DetailPanelProvider>
      <AppLayoutInner />
    </DetailPanelProvider>
  );
}
