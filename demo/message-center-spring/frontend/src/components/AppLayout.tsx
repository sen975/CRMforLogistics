import { useEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Layout, Button, Drawer, Typography, Space, Tooltip, theme } from 'antd';
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
} from '@ant-design/icons';
import { useAuth } from '../hooks/useAuth';
import { DetailPanelProvider, useDetailPanel } from '../hooks/useDetailPanel';
import ContactsPage from '../pages/ContactsPage';
import ContactDetailPanel from './ContactDetailPanel';
import CallRecordDetail from './CallRecordDetail';
import { AccountPanel } from './AccountPanel';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

function AppLayoutInner() {
  const { username, logout, isAdmin, canBroadcast } = useAuth();
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
  const detailScope = isThreadPage ? 'thread' : isPhoneRepositoryPage ? 'phone-repository' : 'none';
  const selectedCallRecordId = selectedDetail?.kind === 'callRecord' ? selectedDetail.id : null;

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
          {navigationButton('发送', <SendOutlined />, () => navigate('/send'))}
          {canBroadcast && navigationButton('群发', <NotificationOutlined />, () => navigate('/broadcasts'))}
          {isAdmin && (
            <>
              {navigationButton('企业微信', <WechatOutlined />, () => navigate('/settings/wecom'))}
              {navigationButton('模板', <FileTextOutlined />, () => navigate('/templates'))}
              {navigationButton('渠道设置', <SettingOutlined />, () => navigate('/settings/channels'))}
            </>
          )}
          {navigationButton('电话仓库', <PhoneOutlined />, () => navigate('/phone-repository'))}
          {(isThreadPage || selectedCallRecordId) && (
            <Button
              icon={detailPanelOpen ? <MenuFoldOutlined /> : <MenuUnfoldOutlined />}
              onClick={toggleDetailPanel}
            />
          )}
          {!isMobile && <Text type="secondary">{username}</Text>}
          <Tooltip title="账号">
            <Button
              aria-label="账号"
              icon={<UserOutlined />}
              onClick={() => setAccountOpen(true)}
              type="text"
            />
          </Tooltip>
          <Tooltip title="退出登录">
            <Button aria-label="退出登录" icon={<LogoutOutlined />} onClick={logout} type="text" />
          </Tooltip>
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
        <AccountPanel username={username} />
      </Drawer>
      <Drawer
        title={selectedCallRecordId ? '电话详情' : '联系人详情'}
        width="min(560px, 100vw)"
        open={isMobile && supportsDetailPanel && detailPanelOpen}
        onClose={() => setDetailPanelOpen(false)}
        styles={{ body: { padding: 0 } }}
      >
        {selectedCallRecordId
          ? <CallRecordDetail callRecordId={selectedCallRecordId} />
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
