import React from "react";
import { useCurrentFrame, interpolate, spring, useVideoConfig, Easing } from "remotion";
import { colors, font, W, H } from "../theme";

// ===== 通用 UI mock 组件 =====
// 所有界面元素用 CSS 重画，避免使用真实截图（合规且便于迭代）

// 通用卡片容器
export const Card: React.FC<{
  x: number; y: number; w: number; h: number;
  bg?: string; radius?: number; border?: string;
  children?: React.ReactNode; style?: React.CSSProperties;
  shadow?: boolean;
}> = ({ x, y, w, h, bg = colors.bgPanel, radius = 16, border, children, style, shadow = true }) => (
  <div
    style={{
      position: "absolute",
      left: x, top: y, width: w, height: h,
      background: bg,
      borderRadius: radius,
      border: border ?? `1px solid ${colors.divider}`,
      boxShadow: shadow ? "0 12px 40px rgba(0,0,0,0.4)" : "none",
      overflow: "hidden",
      fontFamily: font.sans,
      color: colors.ink,
      ...style,
    }}
  >
    {children}
  </div>
);

// 头像（圆形 + 首字母）
// inline=true 时走文档流（父容器是 flex 行时用），否则绝对定位自由摆放
export const Avatar: React.FC<{ x?: number; y?: number; size?: number; bg: string; label?: string; src?: string; outline?: boolean; inline?: boolean }> = ({ x = 0, y = 0, size = 56, bg, label, outline, inline }) => (
  <div
    style={{
      position: inline ? "relative" : "absolute",
      left: inline ? undefined : x,
      top: inline ? undefined : y,
      width: size, height: size, flex: "0 0 auto",
      borderRadius: "50%", background: bg,
      display: "flex", alignItems: "center", justifyContent: "center",
      color: colors.ink, fontWeight: 700, fontSize: size * 0.4,
      border: outline ? `2px solid ${colors.ink}` : "none",
    }}
  >
    {label ?? ""}
  </div>
);

// 对话气泡
export const Bubble: React.FC<{
  x: number; y: number; w?: number;
  text: string; bg?: string; fg?: string;
  tail?: "left" | "right";
}> = ({ x, y, w = 360, text, bg = colors.bgPanel2, fg = colors.ink, tail = "left" }) => (
  <div
    style={{
      position: "absolute", left: x, top: y,
      maxWidth: w, padding: "14px 18px",
      background: bg, color: fg,
      borderRadius: 18,
      fontSize: 22, lineHeight: 1.45,
      fontFamily: font.sans,
    }}
  >
    {text}
  </div>
);

// 列表项
export const Row: React.FC<{ x: number; y: number; w: number; h?: number; left?: React.ReactNode; center?: React.ReactNode; right?: React.ReactNode }> = ({ x, y, w, h = 60, left, center, right }) => (
  <div
    style={{
      position: "absolute", left: x, top: y, width: w, height: h,
      display: "flex", alignItems: "center",
      padding: "0 16px",
      borderBottom: `1px solid ${colors.divider}`,
    }}
  >
    <div style={{ flex: "0 0 auto" }}>{left}</div>
    <div style={{ flex: 1, marginLeft: 16, color: colors.ink, fontSize: 18 }}>{center}</div>
    <div style={{ flex: "0 0 auto", color: colors.inkSoft, fontSize: 14 }}>{right}</div>
  </div>
);

// 五图标条（开场素材）
export const AppStrip: React.FC<{ y?: number; gap?: number; iconSize?: number }> = ({ y = H / 2 - 60, gap = 36, iconSize = 110 }) => {
  const apps = [
    { name: "企业微信", color: colors.wecom, letter: "W" },
    { name: "WhatsApp", color: colors.whatsapp, letter: "A" },
    { name: "邮箱", color: colors.email, letter: "M" },
    { name: "电话", color: colors.phone, letter: "T" },
    { name: "笔记本", color: colors.inkDim, letter: "N" },
  ];
  const total = apps.length * iconSize + (apps.length - 1) * gap;
  const startX = (W - total) / 2;
  return (
    <div style={{ position: "absolute", top: y, left: 0, right: 0 }}>
      {apps.map((a, i) => {
        const x = startX + i * (iconSize + gap);
        return (
          <div
            key={i}
            style={{
              position: "absolute", left: x, top: 0,
              width: iconSize, height: iconSize,
              borderRadius: 24, background: a.color,
              display: "flex", alignItems: "center", justifyContent: "center",
              fontSize: 52, fontWeight: 800, color: "#fff",
              boxShadow: "0 12px 30px rgba(0,0,0,0.4)",
              fontFamily: font.display,
            }}
          >
            {a.letter}
            <div style={{ position: "absolute", bottom: -36, left: 0, right: 0, textAlign: "center", color: colors.inkSoft, fontSize: 18 }}>{a.name}</div>
          </div>
        );
      })}
    </div>
  );
};

// 入场动画 wrapper（translateY + opacity）
export const FadeIn: React.FC<{
  frame: number; start: number; dur?: number; fromY?: number; children: React.ReactNode;
}> = ({ frame, start, dur = 18, fromY = 24, children }) => {
  const t = frame - start;
  if (t < 0) return null;
  const opacity = interpolate(t, [0, dur], [0, 1], { extrapolateRight: "clamp", easing: Easing.out(Easing.cubic) });
  const y = interpolate(t, [0, dur], [fromY, 0], { extrapolateRight: "clamp", easing: Easing.out(Easing.cubic) });
  return <div style={{ position: "absolute", inset: 0, opacity, transform: `translateY(${y}px)` }}>{children}</div>;
};

// 渐变舞台背景
export const Stage: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div
    style={{
      position: "absolute", inset: 0,
      background: `radial-gradient(ellipse at 50% 40%, #1a2540 0%, ${colors.bgDeep} 60%, #050810 100%)`,
      overflow: "hidden",
    }}
  >
    {children}
  </div>
);

// 微弱光晕
export const Glow: React.FC<{ x: number; y: number; r: number; color?: string; opacity?: number }> = ({ x, y, r, color = colors.accent, opacity = 0.18 }) => (
  <div
    style={{
      position: "absolute", left: x - r, top: y - r,
      width: r * 2, height: r * 2,
      borderRadius: "50%",
      background: `radial-gradient(circle, ${color} 0%, transparent 70%)`,
      opacity,
      filter: "blur(20px)",
    }}
  />
);

// macOS dock 占位（用于唤起企微场景）
export const Dock: React.FC<{ y?: number; items?: { letter: string; color: string; name: string }[] }> = ({ y = H - 110, items }) => {
  const list = items ?? [
    { letter: "F", color: "#0ea5e9", name: "Finder" },
    { letter: "S", color: "#9ca3af", name: "Safari" },
    { letter: "W", color: colors.wecom, name: "企业微信" },
    { letter: "A", color: colors.whatsapp, name: "WhatsApp" },
    { letter: "M", color: colors.email, name: "Mail" },
  ];
  const dockW = list.length * 84 + (list.length - 1) * 18;
  const startX = (W - dockW) / 2;
  return (
    <div style={{ position: "absolute", left: 0, right: 0, top: y, height: 90 }}>
      <div style={{ position: "absolute", left: startX - 12, top: 6, width: dockW + 24, height: 78, background: "rgba(255,255,255,0.08)", borderRadius: 22, backdropFilter: "blur(20px)", border: `1px solid rgba(255,255,255,0.1)` }} />
      {list.map((it, i) => (
        <div
          key={i}
          style={{
            position: "absolute", left: startX + i * (84 + 18), top: 16,
            width: 70, height: 70, borderRadius: 16,
            background: it.color,
            display: "flex", alignItems: "center", justifyContent: "center",
            color: "#fff", fontWeight: 800, fontSize: 32,
            fontFamily: font.display,
          }}
        >
          {it.letter}
        </div>
      ))}
    </div>
  );
};

// 标记素材占位的小标签（用于提示「找这些素材」页）
export const AssetChip: React.FC<{ label: string; x: number; y: number }> = ({ label, x, y }) => (
  <div
    style={{
      position: "absolute", left: x, top: y,
      padding: "10px 18px",
      background: "rgba(251,191,36,0.15)",
      color: colors.accent,
      borderRadius: 999,
      fontSize: 22, fontWeight: 600,
      fontFamily: font.sans,
      border: `1px solid ${colors.accent}`,
    }}
  >
    {label}
  </div>
);