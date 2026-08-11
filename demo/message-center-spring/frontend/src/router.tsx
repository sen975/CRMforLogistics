import { createBrowserRouter, Navigate } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import LoginPage from './pages/LoginPage';
import HomePage from './pages/HomePage';
import ThreadPage from './pages/ThreadPage';
import SendPage from './pages/SendPage';
import TemplatesPage from './pages/TemplatesPage';
import ChannelSettingsPage from './pages/ChannelSettingsPage';
import PhoneRepositoryPage from './pages/PhoneRepositoryPage';
import { AuthProvider } from './hooks/useAuth';

function AuthGuard({ children }: { children: React.ReactNode }) {
  const token = localStorage.getItem('token');
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export const router = createBrowserRouter([
  {
    path: '/login',
    element: (
      <AuthProvider>
        <LoginPage />
      </AuthProvider>
    ),
  },
  {
    path: '/',
    element: (
      <AuthProvider>
        <AuthGuard>
          <AppLayout />
        </AuthGuard>
      </AuthProvider>
    ),
    children: [
      { index: true, element: <HomePage /> },
      { path: 'thread/:contactId', element: <ThreadPage /> },
      { path: 'send', element: <SendPage /> },
      { path: 'templates', element: <TemplatesPage /> },
      { path: 'settings/channels', element: <ChannelSettingsPage /> },
      { path: 'phone-repository', element: <PhoneRepositoryPage /> },
    ],
  },
]);
