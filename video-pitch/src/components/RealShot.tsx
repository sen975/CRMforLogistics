import React from "react";
import { Img, staticFile, interpolate, Easing } from "remotion";
import { colors } from "../theme";

/**
 * 真截图展示组件。
 *
 * 两张截图是 3200×1800（@2x），容器是 16:9 —— 比例一致，所以直接按容器尺寸铺满，
 * 再用 transform 把「要看的那块」推到画面中心（影视飓风那类片子里最常见的运镜）。
 *
 * 为什么不用 clip-path：transform 走 GPU，且能配合 scale 做缓入缓出，
 * clip 每帧都要重算路径，长镜头下更容易掉帧。
 */
export type Focus = {
  /** 焦点在截图里的归一化坐标（0–1） */
  x: number;
  y: number;
  /** 放大倍数，1 = 整图铺满容器 */
  zoom: number;
};

type Props = {
  /** 相对 public/ 的路径，例如 "shots/03-david-channels.png" */
  file: string;
  /** 场景内的局部帧号 */
  local: number;
  /** 运镜起止帧（局部） */
  from: number;
  to: number;
  focusFrom?: Focus;
  focusTo?: Focus;
  x?: number;
  y?: number;
  w?: number;
  h?: number;
  radius?: number;
  /** 外发光描边颜色，深色舞台上建议给一个，让浅色截图「浮」起来 */
  glow?: string;
  /** 入场动画：从下方浮起 */
  appearAt?: number;
};

export const RealShot: React.FC<Props> = ({
  file,
  local,
  from,
  to,
  focusFrom,
  focusTo,
  x = 284,
  y = 60,
  w = 1351,
  h = 760,
  radius = 18,
  glow = "rgba(255,255,255,0.10)",
  appearAt = 0,
}) => {
  const t = interpolate(local, [from, to], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.inOut(Easing.cubic),
  });

  const f0 = focusFrom ?? { x: 0.5, y: 0.5, zoom: 1 };
  const f1 = focusTo ?? f0;

  const zoom = interpolate(t, [0, 1], [f0.zoom, f1.zoom]);
  const fx = interpolate(t, [0, 1], [f0.x, f1.x]) * w;
  const fy = interpolate(t, [0, 1], [f0.y, f1.y]) * h;

  // transform-origin 固定 0 0，把焦点搬到容器中心
  const tx = w / 2 - fx * zoom;
  const ty = h / 2 - fy * zoom;

  const enter = interpolate(local, [appearAt, appearAt + 24], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.out(Easing.cubic),
  });

  return (
    <div
      style={{
        position: "absolute",
        left: x,
        top: y + (1 - enter) * 40,
        width: w,
        height: h,
        borderRadius: radius,
        overflow: "hidden",
        opacity: enter,
        background: "#ffffff",
        boxShadow: `0 40px 110px rgba(0,0,0,0.7), 0 0 0 1px ${glow}`,
      }}
    >
      <Img
        src={staticFile(file)}
        style={{
          position: "absolute",
          left: 0,
          top: 0,
          width: w,
          height: h,
          transform: `translate(${tx}px, ${ty}px) scale(${zoom})`,
          transformOrigin: "0 0",
        }}
      />
      {/* 浅色截图压在深色舞台上容易「刺眼」，加一层极淡的暖色叠加统一色温 */}
      <div
        style={{
          position: "absolute",
          inset: 0,
          background: "linear-gradient(180deg, rgba(10,14,26,0.06) 0%, rgba(10,14,26,0.0) 40%)",
          pointerEvents: "none",
        }}
      />
    </div>
  );
};

/**
 * 从真截图里「抠」出一块做特写（例如右侧详情栏）。
 * 与 RealShot 的区别：它固定显示某个矩形区域，不做运镜，适合在叠合镜头里当配角卡。
 */
export const ShotCrop: React.FC<{
  file: string;
  local: number;
  x: number;
  y: number;
  w: number;
  h: number;
  /** 要显示的源区域，归一化坐标 */
  sx: number;
  sy: number;
  sw: number;
  sh: number;
  appearAt?: number;
  radius?: number;
}> = ({ file, local, x, y, w, h, sx, sy, sw, sh, appearAt = 0, radius = 14 }) => {
  const enter = interpolate(local, [appearAt, appearAt + 20], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
    easing: Easing.out(Easing.cubic),
  });
  // 源图按 1600×900 计
  const SW = 1600;
  const SH = 900;
  const scale = w / (sw * SW);

  return (
    <div
      style={{
        position: "absolute",
        left: x,
        top: y,
        width: w,
        height: h,
        borderRadius: radius,
        overflow: "hidden",
        opacity: enter,
        transform: `translateY(${(1 - enter) * 24}px)`,
        background: "#fff",
        boxShadow: "0 24px 70px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.10)",
      }}
    >
      <Img
        src={staticFile(file)}
        style={{
          position: "absolute",
          left: -sx * SW * scale,
          top: -sy * SH * scale,
          width: SW * scale,
          height: SH * scale,
        }}
      />
    </div>
  );
};

export const shotBg = colors.bgDeep;
