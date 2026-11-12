import React from "react";
import { useCurrentFrame, interpolate, spring, useVideoConfig, Easing } from "remotion";
import { colors, font, caption, W, H } from "../theme";

// 大字幕组件。支持用 **token** 标记的高亮文本。
// tokens: string[] —— 字幕行的列表，按出现顺序展示。
// 每行可包含以 ** 包围的 token 段落，token 部分渲染为 accent 色。

type Line = { text: string; emphasize?: string; when?: number };

export const Caption: React.FC<{
  lines: Line[];
  frame: number;
  lineStartAt: number[];      // 每行的进场帧
  durationPerLine?: number;   // 每行停留多少帧（默认 60）
  align?: "left" | "center";
  x?: number;
  y?: number;
  size?: keyof typeof caption;
  maxWidth?: number;
}> = ({ lines, frame, lineStartAt, durationPerLine = 90, align = "center", x = W / 2, y = H - 280, size = "hero", maxWidth = 1700 }) => {
  // 当前正在显示的行 = 最近一个 "lineStartAt[i] + durationPerLine > frame" 的 i
  let activeIdx = -1;
  for (let i = 0; i < lineStartAt.length; i++) {
    const s = lineStartAt[i];
    if (s !== undefined && frame >= s && frame < s + durationPerLine) {
      activeIdx = i;
    }
  }
  if (activeIdx === -1) return null;
  const line = lines[activeIdx];
  if (!line) return null;

  const localFrame = frame - (lineStartAt[activeIdx] ?? 0);
  const fadeIn = interpolate(localFrame, [0, 12], [0, 1], { extrapolateRight: "clamp", easing: Easing.out(Easing.cubic) });
  const fadeOut = interpolate(localFrame, [durationPerLine - 12, durationPerLine], [1, 0], { extrapolateLeft: "clamp" });
  const opacity = Math.min(fadeIn, fadeOut);

  const cfg = caption[size];
  const parts = parseTokens(line.text, line.emphasize);

  return (
    <div
      style={{
        position: "absolute",
        left: align === "center" ? x : x,
        top: y,
        transform: align === "center" ? "translate(-50%, 0)" : "translate(0, 0)",
        fontFamily: font.display,
        fontSize: cfg.size,
        fontWeight: cfg.weight,
        lineHeight: cfg.lineHeight,
        color: colors.ink,
        textAlign: align,
        maxWidth,
        opacity,
        textShadow: "0 4px 24px rgba(0,0,0,0.55)",
      }}
    >
      {parts.map((p, i) => (
        <span key={i} style={{ color: p.accent ? colors.accent : colors.ink }}>{p.text}</span>
      ))}
    </div>
  );
};

function parseTokens(text: string, extra?: string): { text: string; accent: boolean }[] {
  const out: { text: string; accent: boolean }[] = [];
  let i = 0;
  while (i < text.length) {
    const j = text.indexOf("**", i);
    if (j === -1) {
      out.push({ text: text.slice(i), accent: !!extra });
      break;
    }
    if (j > i) out.push({ text: text.slice(i, j), accent: !!extra });
    const k = text.indexOf("**", j + 2);
    if (k === -1) {
      out.push({ text: text.slice(j), accent: false });
      break;
    }
    out.push({ text: text.slice(j + 2, k), accent: true });
    i = k + 2;
  }
  return out;
}

// 单行大字幕（用于金句、强调），不带切换
export const StaticCaption: React.FC<{
  text: string;
  frame: number;
  appearAt?: number;
  size?: keyof typeof caption;
  x?: number;
  y?: number;
  color?: string;
  align?: "left" | "center";
  maxWidth?: number;
}> = ({ text, frame, appearAt = 0, size = "hero", x = W / 2, y = H / 2, color, align = "center", maxWidth = 1700 }) => {
  const t = frame - appearAt;
  if (t < 0) return null;
  const opacity = interpolate(t, [0, 18, 220, 260], [0, 1, 1, 0], { extrapolateRight: "clamp" });
  const yShift = interpolate(t, [0, 18], [20, 0], { extrapolateRight: "clamp" });
  const cfg = caption[size];
  const parts = parseTokens(text);
  return (
    <div
      style={{
        position: "absolute",
        left: x,
        top: y,
        transform: `translate(${(align === "center" ? "-50%" : "0%")}, ${yShift}px)`,
        fontFamily: font.display,
        fontSize: cfg.size,
        fontWeight: cfg.weight,
        lineHeight: cfg.lineHeight,
        color: color ?? colors.ink,
        textAlign: align,
        maxWidth,
        opacity,
        textShadow: "0 6px 28px rgba(0,0,0,0.6)",
      }}
    >
      {parts.map((p, i) => (
        <span key={i} style={{ color: p.accent ? colors.accent : (color ?? colors.ink) }}>{p.text}</span>
      ))}
    </div>
  );
};

// 装饰：底部进度条（章节分割）
export const SectionMark: React.FC<{ frame: number; start: number; end: number; label: string }> = ({ frame, start, end, label }) => {
  const visible = frame >= start && frame < end;
  if (!visible) return null;
  const local = frame - start;
  const fade = interpolate(local, [0, 12], [0, 1], { extrapolateRight: "clamp" });
  const width = (end - start) * 30;
  const x = start * 30;
  const progW = Math.min(local, end - start - start) * 30;
  return (
    <div style={{ position: "absolute", bottom: 32, left: 60, fontFamily: font.sans, opacity: fade, color: colors.inkSoft, fontSize: 18 }}>
      <div style={{ width: 6, height: 6, borderRadius: 3, background: colors.accent, display: "inline-block", marginRight: 12, verticalAlign: "middle" }} />
      {label}
      <div style={{ marginTop: 8, width: width, height: 2, background: colors.divider, position: "relative" }}>
        <div style={{ position: "absolute", left: 0, top: 0, width: Math.min(progW, width), height: 2, background: colors.accent }} />
      </div>
    </div>
  );
};