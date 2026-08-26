import { lazy, Suspense, type ReactNode } from 'react';
import { createBrowserRouter, Navigate, useParams } from 'react-router-dom';
import { Button, Result, Spin } from 'antd';
import { AuthProvider } from './hooks/useAuth';

const AppLayout = lazy(() => import('./components/AppLayout'));
const LoginPage = lazy(() => import('./pages/LoginPage'));
const HomePage = lazy(() => import('./pages/HomePage'));
const ThreadPage = lazy(() => import('./pages/ThreadPage'));
const ConversationWorkspace = lazy(() => import('./pages/ConversationWorkspace'));
const SendPage = lazy(() => import('./pages/SendPage'));
const TemplatesPage = lazy(() => import('./pages/TemplatesPage'));
const ChannelSettingsPage = lazy(() => import('./pages/ChannelSettingsPage'));
const WeComManagementPage = lazy(() => import('./pages/WeComManagementPage'));
const PhoneRepositoryPage = lazy(() => import('./pages/PhoneRepositoryPage'));
const BroadcastsPage = lazy(() => import('./pages/BroadcastsPage'));

function AuthGuard({ children }: { children: ReactNode }) {
  const token = localStorage.getItem('token');
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function RouteLoadingFallback({ fullPage = false }: { fullPage?: boolean }) {
  return (
    <div
      style={{
        alignItems: 'center',
        display: 'flex',
        justifyContent: 'center',
        minHeight: fullPage ? '100vh' : '100%',
        width: '100%',
      }}
    >
      <Spin />
    </div>
  );
}

function RouteBoundary({ children, fullPage = false }: { children: ReactNode; fullPage?: boolean }) {
  return (
    <Suspense fallback={<RouteLoadingFallback fullPage={fullPage} />}>
      {children}
    </Suspense>
  );
}

function RouteLoadError() {
  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center' }}>
      <Result
        status="error"
        title="页面加载失败"
        subTitle="请重新加载页面后重试。"
        extra={(
          <Button type="primary" onClick={() => window.location.reload()}>
            重新加载
          </Button>
        )}
      />
    </div>
  );
}

function LegacyThreadRedirect() {
  const { contactId } = useParams();
  return <Navigate to={`/conversations/contact/${contactId ?? ''}`} replace />;
}

export const router = createBrowserRouter([
  {
    path: '/login',
    element: (
      <AuthProvider>
        <RouteBoundary fullPage>
          <LoginPage />
        </RouteBoundary>
      </AuthProvider>
    ),
    errorElement: <RouteLoadError />,
  },
  {
    path: '/',
    element: (
      <AuthProvider>
        <AuthGuard>
          <RouteBoundary fullPage>
            <AppLayout />
          </RouteBoundary>
        </AuthGuard>
      </AuthProvider>
    ),
    errorElement: <RouteLoadError />,
    children: [
      { index: true, element: <RouteBoundary><HomePage /></RouteBoundary> },
      { path: 'conversations/contact/:contactId', element: <RouteBoundary><ConversationWorkspace /></RouteBoundary> },
      { path: 'conversations/wecom-group/:sourceConversationId', element: <RouteBoundary><ConversationWorkspace /></RouteBoundary> },
      { path: 'thread/:contactId', element: <LegacyThreadRedirect /> },
      { path: 'send', element: <RouteBoundary><SendPage /></RouteBoundary> },
      { path: 'broadcasts', element: <RouteBoundary><BroadcastsPage /></RouteBoundary> },
      { path: 'templates', element: <RouteBoundary><TemplatesPage /></RouteBoundary> },
      { path: 'settings/channels', element: <RouteBoundary><ChannelSettingsPage /></RouteBoundary> },
      { path: 'settings/wecom', element: <RouteBoundary><WeComManagementPage /></RouteBoundary> },
      { path: 'phone-repository', element: <RouteBoundary><PhoneRepositoryPage /></RouteBoundary> },
    ],
  },
]);
