import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { login as loginApi, logout as logoutApi } from '../api/endpoints';

interface AuthState {
  token: string | null;
  username: string | null;
  roles: string[];
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  isAuthenticated: boolean;
  isAdmin: boolean;
}

const AuthContext = createContext<AuthState>({
  token: null,
  username: null,
  roles: [],
  login: async () => {},
  logout: async () => {},
  isAuthenticated: false,
  isAdmin: false,
});

function readStoredRoles(): string[] {
  const storedRoles = localStorage.getItem('roles');
  if (!storedRoles) return [];
  try {
    const roles = JSON.parse(storedRoles);
    return Array.isArray(roles) && roles.every((role) => typeof role === 'string') ? roles : [];
  } catch {
    return [];
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem('token'));
  const [username, setUsername] = useState<string | null>(() => localStorage.getItem('username'));
  const [roles, setRoles] = useState<string[]>(readStoredRoles);
  const navigate = useNavigate();

  const login = useCallback(async (u: string, p: string) => {
    const res = await loginApi({ username: u, password: p });
    localStorage.setItem('token', res.token);
    localStorage.setItem('username', res.username);
    localStorage.setItem('roles', JSON.stringify(res.roles));
    setToken(res.token);
    setUsername(res.username);
    setRoles(res.roles);
    navigate('/');
  }, [navigate]);

  const logout = useCallback(async () => {
    try {
      await logoutApi();
    } finally {
      localStorage.removeItem('token');
      localStorage.removeItem('username');
      localStorage.removeItem('roles');
      setToken(null);
      setUsername(null);
      setRoles([]);
      navigate('/login');
    }
  }, [navigate]);

  const value = useMemo(
    () => ({
      token,
      username,
      roles,
      login,
      logout,
      isAuthenticated: !!token,
      isAdmin: roles.includes('ADMIN'),
    }),
    [token, username, roles, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
