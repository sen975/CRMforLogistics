// 单一来源：颜色 / 字体 / 时长 / 分镜边界
// 改这里一处，整片同步

export const FPS = 30;
export const W = 1920;
export const H = 1080;

// 色板
export const colors = {
  bgDeep: "#0a0e1a",        // 深蓝近黑，舞台底色
  bgPanel: "#111827",       // 卡片底
  bgPanel2: "#1f2937",      // 次级卡
  ink: "#f5f7fb",           // 主文字
  inkSoft: "#9aa3b2",       // 次文字
  inkDim: "#6b7280",        // 弱文字
  accent: "#fbbf24",        // 字幕高亮 / 重点
  accent2: "#22d3ee",       // 企微蓝 / 信任色
  wecom: "#10b981",         // 企业微信绿
  whatsapp: "#22c55e",      // WA 绿
  email: "#3b82f6",         // 邮件蓝
  phone: "#a855f7",         // 电话紫
  divider: "#1f2937",
  danger: "#ef4444",
  success: "#22c55e",
};

// 时长（秒）。改这里就改了分镜。
export const S = {
  opening: { start: 0, end: 22 },        // 0-660f
  scattered: { start: 22, end: 60 },     // 660-1800f
  assistant: { start: 60, end: 95 },     // 1800-2850f
  wecom: { start: 95, end: 135 },        // 2850-4050f
  memory: { start: 135, end: 165 },      // 4050-4950f
  montage: { start: 165, end: 180 },     // 4950-5400f
  outro: { start: 180, end: 195 },       // 5400-5850f
  assets: { start: 195, end: 216 },      // 5850-6480f
};

// 字体
export const font = {
  sans: "system-ui, -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif",
  display: "system-ui, -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif",
};

// 字幕三档
export const caption = {
  hero: { size: 96, weight: 800, lineHeight: 1.18 },
  big: { size: 72, weight: 700, lineHeight: 1.2 },
  mid: { size: 56, weight: 600, lineHeight: 1.3 },
  small: { size: 36, weight: 500, lineHeight: 1.4 },
};