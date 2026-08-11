import { useEffect } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Layout, Button, Typography, Space, theme } from 'antd';
import {
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  SendOutlined,
  FileTextOutlined,
  SettingOutlined,
  PhoneOutlined,
} from '@ant-design/icons';
import { useAuth } from '../hooks/useAuth';
import { DetailPanelProvider, useDetailPanel } from '../hooks/useDetailPanel';
import ContactsPage from '../pages/ContactsPage';
import ContactDetailPanel from './ContactDetailPanel';
import CallRecordDetail from './CallRecordDetail';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

function AppLayoutInner() {
  const { username, logout, isAdmin } = useAuth();
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

  const isThreadPage = location.pathname.startsWith('/thread/');
  const isPhoneRepositoryPage = location.pathname === '/phone-repository';
  const supportsDetailPanel = isThreadPage || isPhoneRepositoryPage;
  const detailScope = isThreadPage ? 'thread' : isPhoneRepositoryPage ? 'phone-repository' : 'none';
  const selectedCallRecordId = selectedDetail?.kind === 'callRecord' ? selectedDetail.id : null;

  useEffect(() => {
    clearSelection();
    setDetailPanelOpen(false);
  }, [detailScope, clearSelection, setDetailPanelOpen]);

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
        <Space>
          <Text strong style={{ fontSize: 16 }}>统一消息中心</Text>
        </Space>
        <Space>
          <Button icon={<SendOutlined />} onClick={() => navigate('/send')}>
            发送
          </Button>
          {isAdmin && (
            <>
              <Button icon={<FileTextOutlined />} onClick={() => navigate('/templates')}>
                模板
              </Button>
              <Button icon={<SettingOutlined />} onClick={() => navigate('/settings/channels')}>
                渠道设置
              </Button>
            </>
          )}
          <Button icon={<PhoneOutlined />} onClick={() => navigate('/phone-repository')}>
            电话仓库
          </Button>
          {(isThreadPage || selectedCallRecordId) && (
            <Button
              icon={detailPanelOpen ? <MenuFoldOutlined /> : <MenuUnfoldOutlined />}
              onClick={toggleDetailPanel}
            />
          )}
          <Text type="secondary">{username}</Text>
          <Button icon={<LogoutOutlined />} onClick={logout} type="text" />
        </Space>
      </Header>
      <Layout style={{ flex: 1, overflow: 'hidden' }}>
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
        <Content
          style={{
            overflow: 'auto',
            padding: 16,
            background: token.colorBgContainer,
          }}
        >
          <Outlet />
        </Content>
        {supportsDetailPanel && (
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
