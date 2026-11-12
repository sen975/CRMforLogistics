import { useEffect, useRef, useState } from 'react';
import { Drawer } from 'antd';
import { AssistantPanel } from './AssistantPanel';

/**
 * 全局助手入口：可拖动的悬浮球 + 会话面板。
 *
 * <h2>为什么挂在 `AppLayout` 而不是每个页面</h2>
 * 「在哪儿都能唤起」是这个功能的价值之一：用户想起一件事时，他很可能正待在联系人列表而不是待办页。
 * 挂在布局上，路由切换不会把它卸掉，对话上下文也就跟着用户走。
 *
 * <h2>为什么不用 antd 的 `FloatButton`</h2>
 * 它只给一个固定角标：位置写死在右下、拖不动、也没有「贴边收半个、鼠标过来再滑出来」这条交互。
 * 而这几件事恰恰是这次要的 —— 所以球自己画。`FloatButton` 的 tooltip 由下面那个 label 接管。
 *
 * <h2>面板的挂载与关闭</h2>
 * 面板**不随关闭卸载**：抽屉关掉只是收起来，对话还在。否则用户手滑点一下外面，
 * 刚说了一半的话和那张还没确认的卡片就一起没了 —— 而那张卡片背后是一次真实的授权。
 */

/** 悬浮球当前用的图标。候选图都在 `public/assistant-icons/`，换图标只改这一行。 */
export const ORB_ICON = '/assistant-icons/E-light-badge.png';

/** 球的直径。下面的几何判定（吸附、藏边、钳制）全部以它为单位。 */
export const ORB_SIZE = 56;
/** 吸附后藏进屏幕外的宽度。鼠标移上来时球滑出这么多 —— 这就是「滑出来」的全部位移。 */
export const ORB_PEEK = 12;
/** 球与屏幕上下边之间的最小距离，避免贴死在角上掰不开。 */
export const ORB_MARGIN = 12;
/** 位移超过它才当拖动；不超过就还是点击。没有它，手抖两像素就再也点不开面板。 */
const DRAG_THRESHOLD = 4;
const STORAGE_KEY = 'mc.assistant.orb.v1';

export type OrbEdge = 'left' | 'right';

/** 球的落位。`snapped` = 已贴住左右某一边；`free` = 正被拖着，还没决定靠哪边。 */
export type OrbPlacement =
  | { kind: 'snapped'; edge: OrbEdge; y: number }
  | { kind: 'free'; x: number; y: number };

/** 把纵坐标夹在可视区内。窗口变矮时（或存了个旧位置）球不许掉到屏幕外面去。 */
export function clampOrbY(y: number, viewportHeight: number): number {
  const max = Math.max(ORB_MARGIN, viewportHeight - ORB_SIZE - ORB_MARGIN);
  return Math.min(Math.max(y, ORB_MARGIN), max);
}

/** 把横坐标夹在可视区内。只在拖动过程中用 —— 松手就吸附，不留半悬在中间的位置。 */
export function clampOrbX(x: number, viewportWidth: number): number {
  return Math.min(Math.max(x, 0), Math.max(0, viewportWidth - ORB_SIZE));
}

/** 松手时决定靠哪边：离中线近的那边。纵向位置原样保留。 */
export function snapOrb(
  x: number,
  y: number,
  viewportWidth: number,
  viewportHeight: number,
): { edge: OrbEdge; y: number } {
  const edge: OrbEdge = x + ORB_SIZE / 2 < viewportWidth / 2 ? 'left' : 'right';
  return { edge, y: clampOrbY(y, viewportHeight) };
}

/** 贴边状态下球的左上角横坐标（**不含**藏进屏幕外的那一段，那段交给 CSS 的 transform）。 */
export function orbLeft(edge: OrbEdge, viewportWidth: number): number {
  return edge === 'left' ? 0 : Math.max(0, viewportWidth - ORB_SIZE);
}

/** 读上次拖到哪儿。坏数据（手工改过、旧版本、隐私模式写不进去）一律当作「没存过」。 */
export function parseStoredOrb(raw: string | null): { edge: OrbEdge; y: number } | null {
  if (!raw) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== 'object' || value === null) return null;
    const { edge, y } = value as { edge?: unknown; y?: unknown };
    if (edge !== 'left' && edge !== 'right') return null;
    if (typeof y !== 'number' || !Number.isFinite(y)) return null;
    return { edge, y };
  } catch {
    return null;
  }
}

function readStoredOrb(): { edge: OrbEdge; y: number } | null {
  try {
    return parseStoredOrb(window.localStorage.getItem(STORAGE_KEY));
  } catch {
    return null;
  }
}

function writeStoredOrb(orb: { edge: OrbEdge; y: number }): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(orb));
  } catch {
    // 存不下就只在本次页面会话里生效。位置是锦上添花，不值得为它报错。
  }
}

interface DragSession {
  pointerId: number;
  startX: number;
  startY: number;
  originX: number;
  originY: number;
  moved: boolean;
}

export function AssistantLauncher() {
  const [open, setOpen] = useState(false);
  const [viewport, setViewport] = useState(() => ({
    width: window.innerWidth,
    height: window.innerHeight,
  }));
  const [placement, setPlacement] = useState<OrbPlacement>(() => {
    const stored = readStoredOrb();
    return stored
      ? { kind: 'snapped', edge: stored.edge, y: stored.y }
      : { kind: 'snapped', edge: 'right', y: clampOrbY(window.innerHeight - ORB_SIZE - 88, window.innerHeight) };
  });
  const [dragging, setDragging] = useState(false);
  const drag = useRef<DragSession | null>(null);

  /*
   * 指针事件的处理函数活在「按下那一刻」的闭包里，而窗口尺寸可能此后变过（拖到一半把窗口拉窄）。
   * 吸附要用的是**当下**的可视区，所以尺寸走 ref 而不是闭包里的 `viewport`。
   */
  const viewportRef = useRef(viewport);
  viewportRef.current = viewport;

  useEffect(() => {
    const onResize = () => setViewport({ width: window.innerWidth, height: window.innerHeight });
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const left = placement.kind === 'free' ? placement.x : orbLeft(placement.edge, viewport.width);
  const top = clampOrbY(placement.y, viewport.height);
  // 拖动中途也按「球心在哪半边」决定 hover 滑出的方向，松手前后的表现才连得上。
  const edge: OrbEdge =
    placement.kind === 'free'
      ? left + ORB_SIZE / 2 < viewport.width / 2
        ? 'left'
        : 'right'
      : placement.edge;

  const handlePointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    drag.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      originX: left,
      originY: top,
      moved: false,
    };
    try {
      event.currentTarget.setPointerCapture(event.pointerId);
    } catch {
      // 指针捕获拿不到时拖动照样能用，只是移出球之后不再跟手。不为它把交互整个禁掉。
    }
  };

  const handlePointerMove = (event: React.PointerEvent<HTMLDivElement>) => {
    const session = drag.current;
    if (!session || session.pointerId !== event.pointerId) return;
    const dx = event.clientX - session.startX;
    const dy = event.clientY - session.startY;
    if (!session.moved) {
      if (Math.hypot(dx, dy) < DRAG_THRESHOLD) return; // 还看不出是「拖」还是「点」，先不动球
      session.moved = true;
      setDragging(true);
    }
    const { width, height } = viewportRef.current;
    setPlacement({
      kind: 'free',
      x: clampOrbX(session.originX + dx, width),
      y: clampOrbY(session.originY + dy, height),
    });
  };

  /**
   * 松手（或指针被系统收走）。
   *
   * 吸附位置直接由「起点 + 指针位移」这一对现场数据算出来，**不读 state**：
   * 抬手和最后一帧 move 可能落在同一批渲染里，那时 state 还停在上一帧的位置，
   * 照它吸附会让球擦着鼠标的落点滑到另一边边缘。
   *
   * `tap` 区分两种收尾：正常抬手时「没动过 = 点击」；`pointercancel` 是系统把指针拿走了
   * （触摸时滚了页面、来电…），那一刻用户并没有点按钮，别替他开面板 —— 但球得照样吸附落地。
   */
  const endDrag = (event: React.PointerEvent<HTMLDivElement>, tap: boolean) => {
    const session = drag.current;
    if (!session || session.pointerId !== event.pointerId) return;
    drag.current = null;
    setDragging(false);
    if (!session.moved) {
      if (tap) setOpen(true);
      return;
    }
    const { width, height } = viewportRef.current;
    const snapped = snapOrb(
      clampOrbX(session.originX + (event.clientX - session.startX), width),
      clampOrbY(session.originY + (event.clientY - session.startY), height),
      width,
      height,
    );
    setPlacement({ kind: 'snapped', ...snapped });
    writeStoredOrb(snapped);
  };

  return (
    <>
      {/*
        外框（`.mc-assistant-orb`）不参与滑动，只有里面的球会滑 —— 这不是为了好看。
        球滑出 12px 之后，它覆盖的像素整体挪了位；如果 hover 判在球身上，鼠标恰好压在被挪走的
        那条 12px 上就会「滑出 → 失去 hover → 滑回」来回抖。hover 判在固定不动的外框上，抖动没了。
      */}
      <div
        className="mc-assistant-orb"
        data-edge={edge}
        data-dragging={dragging ? 'true' : undefined}
        style={{ left, top }}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={(event) => endDrag(event, true)}
        onPointerCancel={(event) => endDrag(event, false)}
      >
        <button
          type="button"
          className="mc-assistant-orb-ball"
          aria-label="AI 助手"
          onClick={() => setOpen(true)}
        >
          <img src={ORB_ICON} alt="" draggable={false} />
          <span className="mc-assistant-orb-label" aria-hidden="true">
            AI 助手
          </span>
        </button>
      </div>
      <Drawer
        title="AI 助手"
        placement="right"
        width="min(420px, 100vw)"
        open={open}
        onClose={() => setOpen(false)}
        /*
         * 不压暗背景，也**不拦**页面上的点击。
         *
         * 以前这里留着 antd 的默认遮罩：面板一开，整页盖上一层灰、且什么都点不动 ——
         * 而助手的用法恰恰是「一边看着它、一边翻联系人」，遮罩把这条路堵死了。
         * 关面板的出口仍在：头部 × 与 Esc（`keyboard` 默认开）。
         */
        mask={false}
        rootClassName="mc-assistant-drawer"
        styles={{ body: { padding: 0 } }}
      >
        <AssistantPanel />
      </Drawer>
    </>
  );
}
