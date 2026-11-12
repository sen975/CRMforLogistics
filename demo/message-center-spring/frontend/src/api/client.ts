import axios from 'axios';
import { API_BASE, authToken, handleUnauthorized } from './session';

/**
 * 走 axios 的那些端点用的实例。
 *
 * 会话设施（前缀、凭证、401 处置）在 `session.ts`：它们不属于 axios，
 * 助手那条 `fetch` 流也要用同一份。
 */
const client = axios.create({
  baseURL: API_BASE,
  withCredentials: true,
});

client.interceptors.request.use((config) => {
  const token = authToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  (r) => r,
  (err) => {
    if (err.response?.status === 401) {
      handleUnauthorized();
    }
    return Promise.reject(err);
  },
);

export default client;
