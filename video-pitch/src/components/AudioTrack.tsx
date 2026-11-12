import React from "react";
import { Audio, Sequence, staticFile } from "remotion";
import { FPS } from "../theme";

/**
 * 整片声音层。
 *
 * 三层结构：
 *   1. BGM —— 低频垫底全程铺，音量压得很低（0.14），只负责「不空」；
 *   2. 配音 —— 目前是 macOS `say` 出的三段草稿（开场 / 态度句 / 结尾），换成真人录音时
 *      只需替换 public/audio/ 下的文件，时间点不用动；
 *   3. 音效 —— 点缀，落在图标滑入、卡片弹出这些动作帧上。
 *
 * 注意：Sequence 里的 Audio 一定要给 durationInFrames，否则 Remotion 在预计算
 * 总时长时会把它当成无限长，渲染队列会一直排不下去。
 */

const sec = (s: number) => Math.round(s * FPS);

export const AudioTrack: React.FC = () => (
  <>
    {/* ── BGM ───────────────────────────────────────────── */}
    <Audio
      src={staticFile("audio/bgm-01-pad-suspense.mp3")}
      volume={0.14}
      loop
    />

    {/* ── 配音（草稿） ───────────────────────────────────── */}
    {/* 开场：0:03 起，接画面里图标落位之后 */}
    <Sequence from={sec(3)} durationInFrames={sec(15)}>
      <Audio src={staticFile("audio/tts-demo-1.mp3")} volume={0.95} />
    </Sequence>

    {/* 态度句：段三「这不是技术做不到，是选择不做」 */}
    <Sequence from={sec(102)} durationInFrames={sec(6.5)}>
      <Audio src={staticFile("audio/tts-demo-2.mp3")} volume={0.95} />
    </Sequence>

    {/* 结尾 */}
    <Sequence from={sec(182)} durationInFrames={sec(12)}>
      <Audio src={staticFile("audio/tts-demo-3.mp3")} volume={0.95} />
    </Sequence>

    {/* ── 音效 ───────────────────────────────────────────── */}
    <Sequence from={sec(1)} durationInFrames={sec(1)}>
      <Audio src={staticFile("audio/sfx-Ping.mp3")} volume={0.30} />
    </Sequence>
    <Sequence from={sec(3.7)} durationInFrames={sec(1)}>
      <Audio src={staticFile("audio/sfx-Tink.mp3")} volume={0.26} />
    </Sequence>
    <Sequence from={sec(6.4)} durationInFrames={sec(1)}>
      <Audio src={staticFile("audio/sfx-Tink.mp3")} volume={0.26} />
    </Sequence>
    <Sequence from={sec(9.1)} durationInFrames={sec(1)}>
      <Audio src={staticFile("audio/sfx-Tink.mp3")} volume={0.26} />
    </Sequence>
    {/* 段二：确认卡片弹出 */}
    <Sequence from={sec(78)} durationInFrames={sec(1)}>
      <Audio src={staticFile("audio/sfx-Pop.mp3")} volume={0.34} />
    </Sequence>
    {/* 段四：标签逐条浮现 */}
    <Sequence from={sec(140)} durationInFrames={sec(1)}>
      <Audio src={staticFile("audio/sfx-Ping.mp3")} volume={0.22} />
    </Sequence>
  </>
);
