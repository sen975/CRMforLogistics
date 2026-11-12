/**
 * 传输层无关的会话设施：API 前缀、取凭证、认证失效怎么办。
 *
 * <h2>为什么它单独一个文件，而不是放在 `client.ts` 里</h2>
 * 两种传输层都要用它：axios 实例（`client.ts`）与助手那条 `fetch` 流（`assistantStream.ts`）。
 * 挂在 `client.ts` 上有两个后果，都不划算：
 * <ol>
 *   <li>流那条路被迫依赖一个它根本不用的 axios 实例；</li>
 *   <li>所有 `vi.mock('./client', () => ({ default: ... }))` 的用例会连这些导出一起被替掉 ——
 *       于是每加一个导出都会让一批与它无关的用例失败。这是实际发生过的：三个既有用例就是这么红的。</li>
 * </ol>
 */

/**
 * API 前缀。
 *
 * 放在这里是为了让两种传输层共用一个值：axios 实例拿它当 `baseURL`，
 * 而 `fetch` 没有那个概念、必须自己拼。少一处「忘了加 `/api`」，
 * 就少一个只在流式端点上出现的 404。
 */
export const API_BASE = '/api';

/** 当前登录凭证，`null` 表示没登录（或已经被清掉）。 */
export function authToken(): string | null {
  return localStorage.getItem('token');
}

/**
 * 认证已失效：清掉本地凭证并回登录页。
 *
 * 两处共用这一份（axios 拦截器 + SSE 客户端）。它不只是三行清理，而是一条**策略**：
 * 清哪几个键、把人送到哪里去。分开写两份早晚会漂成两种行为 ——
 * 普通请求会跳登录页、发消息那条却只显示一句看不出原因的失败，而用户分不出这两者的区别，
 * 也就不知道自己该重新登录。
 */
export function handleUnauthorized(): void {
  localStorage.removeItem('token');
  localStorage.removeItem('username');
  localStorage.removeItem('roles');
  window.location.href = '/login';
}
