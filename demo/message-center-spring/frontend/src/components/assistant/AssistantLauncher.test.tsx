import '@testing-library/jest-dom/vitest';
import { App as AntApp } from 'antd';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AssistantLauncher,
  ORB_ICON,
  ORB_MARGIN,
  ORB_SIZE,
  clampOrbX,
  clampOrbY,
  orbLeft,
  parseStoredOrb,
  snapOrb,
} from './AssistantLauncher';

/**
 * 悬浮球的「几何」与「拖动收尾」。
 *
 * <p>这一层值钱的地方在于：球的落位是一串算术（贴哪边、藏多少、不许出界），
 * 算错了在界面上**不报错**，只是球停在一个别扭的位置 —— 而这正是它最容易悄悄坏掉的方式。
 * 所以坐标换算全部提成纯函数，在这里逐条钉住；视觉（滑出动画、悬停标签）由 CDP 真渲染负责
 * （`docs/ui-mockups/`）。
 *
 * <p>窗口尺寸用的是 jsdom 的默认 1024×768 —— 下面几处期望值都由它算出来。
 */

const api = vi.hoisted(() => ({
  sendAssistantMessage: vi.fn(),
  confirmAssistantAction: vi.fn(),
  cancelAssistantAction: vi.fn(),
  fetchAssistantConversation: vi.fn(),
  fetchAssistantConversations: vi.fn(),
  fetchLatestAssistantConversation: vi.fn(),
  createAssistantConversation: vi.fn(),
  openAssistantConversation: vi.fn(),
  deleteAssistantConversation: vi.fn(),
  fetchTemplateMediaUpload: vi.fn(),
  uploadTemplateMedia: vi.fn(),
}));
vi.mock('../templates/templateMediaUpload', () => ({ recoverTemplateMediaUpload: vi.fn() }));
vi.mock('../../api/endpoints', () => api);

const STORAGE_KEY = 'mc.assistant.orb.v1';
const VIEWPORT_WIDTH = 1024;
const VIEWPORT_HEIGHT = 768;

const orb = () => document.querySelector('.mc-assistant-orb') as HTMLElement;

function renderLauncher() {
  return render(
    <AntApp>
      <AssistantLauncher />
    </AntApp>,
  );
}

/** 手动派发指针事件。jsdom 没有 `PointerEvent`，用 `MouseEvent` 顶上 —— React 只认事件名与坐标。 */
function pointer(type: 'pointerdown' | 'pointermove' | 'pointerup', x: number, y: number) {
  const target = orb();
  act(() => {
    target.dispatchEvent(new MouseEvent(type, { bubbles: true, clientX: x, clientY: y }));
  });
}

beforeEach(() => {
  window.localStorage.clear();
  api.fetchAssistantConversation.mockResolvedValue([]);
  api.fetchAssistantConversations.mockResolvedValue([]);
  // 服务端明确说「还没有任何对话」。返回 undefined 会让 useAssistant 在 `.conversationId`
  // 上炸掉，测试照样绿、但控制台会留一条与本次改动无关的假错误。
  api.fetchLatestAssistantConversation.mockResolvedValue({ conversationId: null });
});

describe('吸附几何', () => {
  it('松手时靠向更近的那一边', () => {
    expect(snapOrb(10, 400, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)).toEqual({ edge: 'left', y: 400 });
    expect(snapOrb(900, 400, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)).toEqual({ edge: 'right', y: 400 });
  });

  it('球心越过中线就算另一边，边界值不许两边都不认', () => {
    // 中线 512 ⇒ 球心 < 512 即「左边」，于是 x < 512 - 28 = 484
    expect(snapOrb(483, 400, VIEWPORT_WIDTH, VIEWPORT_HEIGHT).edge).toBe('left');
    expect(snapOrb(484, 400, VIEWPORT_WIDTH, VIEWPORT_HEIGHT).edge).toBe('right');
  });

  it('纵坐标夹在可视区内，上下各留出边距', () => {
    expect(clampOrbY(-100, VIEWPORT_HEIGHT)).toBe(ORB_MARGIN);
    expect(clampOrbY(10_000, VIEWPORT_HEIGHT)).toBe(VIEWPORT_HEIGHT - ORB_SIZE - ORB_MARGIN);
  });

  it('视口比球还矮时不许算出负数，球留在上沿', () => {
    // max 会退化成下沿 < 上沿，此时以 ORB_MARGIN 为准（宁可压住下沿，也不许把球扔到屏幕外）
    expect(clampOrbY(500, 40)).toBe(ORB_MARGIN);
  });

  it('拖动中横向也不许出界', () => {
    expect(clampOrbX(-30, VIEWPORT_WIDTH)).toBe(0);
    expect(clampOrbX(9999, VIEWPORT_WIDTH)).toBe(VIEWPORT_WIDTH - ORB_SIZE);
  });

  it('贴左右两边时球左上角的横坐标', () => {
    expect(orbLeft('left', VIEWPORT_WIDTH)).toBe(0);
    expect(orbLeft('right', VIEWPORT_WIDTH)).toBe(VIEWPORT_WIDTH - ORB_SIZE);
  });
});

describe('读上次的位置', () => {
  it('没存过、存坏了、存的是旧格式 —— 一律当没存过', () => {
    expect(parseStoredOrb(null)).toBeNull();
    expect(parseStoredOrb('{')).toBeNull();
    expect(parseStoredOrb('null')).toBeNull();
    expect(parseStoredOrb('"left"')).toBeNull();
    expect(parseStoredOrb('{}')).toBeNull();
    expect(parseStoredOrb('{"edge":"middle","y":10}')).toBeNull();
    expect(parseStoredOrb('{"edge":"left","y":"10"}')).toBeNull();
    expect(parseStoredOrb('{"edge":"left","y":null}')).toBeNull();
    // NaN / Infinity 不能当坐标用：它们会让 style.top 变成 "Infinitypx"，球直接消失。
    expect(parseStoredOrb('{"edge":"left","y":1e999}')).toBeNull();
  });

  it('存过的位置原样读回来', () => {
    expect(parseStoredOrb('{"edge":"left","y":321}')).toEqual({ edge: 'left', y: 321 });
  });
});

describe('悬浮球', () => {
  it('默认贴在右下角，并挂上挑好的那张图', () => {
    renderLauncher();

    expect(orb()).toHaveAttribute('data-edge', 'right');
    expect(orb().style.left).toBe(`${VIEWPORT_WIDTH - ORB_SIZE}px`);
    expect(orb().style.top).toBe(`${VIEWPORT_HEIGHT - ORB_SIZE - 88}px`);

    const ball = screen.getByRole('button', { name: 'AI 助手' });
    expect(ball.querySelector('img')).toHaveAttribute('src', ORB_ICON);
    // 标签是给眼睛看的，别被读屏读成第二个名字
    expect(ball.querySelector('.mc-assistant-orb-label')).toHaveAttribute('aria-hidden', 'true');
  });

  it('拖到左半边松手 → 吸附到左边并记住位置', () => {
    renderLauncher();

    pointer('pointerdown', 990, 650);
    pointer('pointermove', 100, 650);

    // 拖动途中球必须跟着指针走，否则「拖」这件事在手里就是断的
    expect(orb()).toHaveAttribute('data-dragging', 'true');
    expect(orb().style.left).toBe('78px');

    pointer('pointerup', 100, 650);

    expect(orb()).toHaveAttribute('data-edge', 'left');
    expect(orb()).not.toHaveAttribute('data-dragging');
    // 松手后不是停在 78px，而是贴住左边缘（藏进屏外的那 12px 交给 CSS 的 transform）
    expect(orb().style.left).toBe('0px');
    // 纵向一步没挪（起点 624 = 768 - 56 - 88），松手后应当原样保留
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe(JSON.stringify({ edge: 'left', y: 624 }));
  });

  it('下次打开时回到上次拖到的位置', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ edge: 'left', y: 200 }));
    renderLauncher();

    expect(orb()).toHaveAttribute('data-edge', 'left');
    expect(orb().style.left).toBe('0px');
    expect(orb().style.top).toBe('200px');
  });

  it('存的位置离谱时（换了更小的屏幕）把球拉回可视区', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ edge: 'right', y: 99_999 }));
    renderLauncher();

    expect(orb().style.top).toBe(`${VIEWPORT_HEIGHT - ORB_SIZE - ORB_MARGIN}px`);
  });

  it('只是点一下（手抖两像素）仍然算点击，不是拖动', async () => {
    renderLauncher();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'AI 助手' }));

    expect(await screen.findByTestId('assistant-panel')).toBeVisible();
    // 没拖动就不该写位置：否则「点一下」会顺手把球挪到它当时所在的位置
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('拖出去又拖回来，松手时不当成点击', () => {
    renderLauncher();

    pointer('pointerdown', 990, 650);
    pointer('pointermove', 500, 650);
    pointer('pointermove', 990, 650);
    pointer('pointerup', 990, 650);

    expect(screen.queryByTestId('assistant-panel')).not.toBeInTheDocument();
  });
});
