import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { exchangeWeComLogin, fetchAccountProfile, login as loginApi, logout as logoutApi,
  register as registerApi } from '../api/endpoints';
import type { AccountProfile, LoginResponse, RegisterRequest } from '../api/types';

interface AuthState {
  token: string | null;
  username: string | null;
  roles: string[];
  profile: AccountProfile | null;
  wecomViewerAuthToken: string | null;
  login: (username: string, password: string) => Promise<void>;
  register: (request: RegisterRequest) => Promise<void>;
  loginWithWeCom: (code: string, state: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshProfile: () => Promise<AccountProfile>;
  replaceSession: (response: LoginResponse) => void;
  isAuthenticated: boolean;
  isAdmin: boolean;
  canBroadcast: boolean;
}

const AuthContext = createContext<AuthState>({
  token: null,
  username: null,
  roles: [],
  profile: null,
  wecomViewerAuthToken: null,
  login: async () => {},
  register: async () => {},
  loginWithWeCom: async () => {},
  logout: async () => {},
  refreshProfile: async () => { throw new Error('AuthProvider is missing'); },
  replaceSession: () => {},
  isAuthenticated: false,
  isAdmin: false,
  canBroadcast: false,
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
  const [profile, setProfile] = useState<AccountProfile | null>(null);
  const [wecomViewerAuthToken, setWeComViewerAuthToken] = useState<string | null>(null);
  const navigate = useNavigate();

  const applySession = useCallback((res: LoginResponse) => {
    localStorage.setItem('token', res.token);
    localStorage.setItem('username', res.username);
    localStorage.setItem('roles', JSON.stringify(res.roles));
    setToken(res.token);
    setUsername(res.username);
    setRoles(res.roles);
    setProfile(null);
  }, []);

  const refreshProfile = useCallback(async () => {
    const next = await fetchAccountProfile();
    setProfile(next);
    localStorage.setItem('username', next.username);
    localStorage.setItem('roles', JSON.stringify(next.roles));
    setUsername(next.username);
    setRoles(next.roles);
    return next;
  }, []);

  useEffect(() => {
    if (!token) {
      setProfile(null);
      return;
    }
    void refreshProfile().catch(() => undefined);
  }, [token, refreshProfile]);

  const login = useCallback(async (u: string, p: string) => {
    const res = await loginApi({ username: u, password: p });
    setWeComViewerAuthToken(null);
    applySession(res);
  }, [applySession]);

  const register = useCallback(async (request: RegisterRequest) => {
    const res = await registerApi(request);
    setWeComViewerAuthToken(null);
    applySession(res);
  }, [applySession]);

  const loginWithWeCom = useCallback(async (code: string, state: string) => {
    const res = await exchangeWeComLogin({ code, state });
    setWeComViewerAuthToken(res.viewerAuthToken);
    applySession(res);
  }, [applySession]);

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
      setProfile(null);
      setWeComViewerAuthToken(null);
      navigate('/login');
    }
  }, [navigate]);

  const value = useMemo(
    () => ({
      token,
      username,
      roles,
      profile,
      wecomViewerAuthToken,
      login,
      register,
      loginWithWeCom,
      logout,
      refreshProfile,
      replaceSession: applySession,
      isAuthenticated: !!token,
      isAdmin: roles.includes('ADMIN'),
      canBroadcast: roles.includes('ADMIN') || roles.includes('BROADCAST_SENDER'),
    }),
    [token, username, roles, profile, wecomViewerAuthToken, login, register, loginWithWeCom,
      logout, refreshProfile, applySession],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
