import React from "react";
import { useCurrentFrame, interpolate, spring, useVideoConfig, Easing, AbsoluteFill, Img, staticFile } from "remotion";
import { colors, font, S, W, H } from "./theme";
import { Caption, StaticCaption, SectionMark } from "./components/Caption";
import { Card, Avatar, Bubble, Row, AppStrip, FadeIn, Stage, Glow, Dock, AssetChip } from "./components/MockUI";
import { RealShot, ShotCrop } from "./components/RealShot";
import { AudioTrack } from "./components/AudioTrack";

// ============================================================================
// 主组合
// ============================================================================

export const PitchVideo: React.FC = () => (
  <AbsoluteFill style={{ background: colors.bgDeep }}>
    <VisualTrack />
    <AudioTrack />
  </AbsoluteFill>
);

const VisualTrack: React.FC = () => {
  const frame = useCurrentFrame();

  // 阶段选择
  if (frame < S.opening.end * 30) return <OpeningScene frame={frame} />;
  if (frame < S.scattered.end * 30) return <ScatteredScene frame={frame} />;
  if (frame < S.assistant.end * 30) return <AssistantScene frame={frame} />;
  if (frame < S.wecom.end * 30) return <WeComScene frame={frame} />;
  if (frame < S.memory.end * 30) return <MemoryScene frame={frame} />;
  if (frame < S.montage.end * 30) return <MontageScene frame={frame} />;
  if (frame < S.outro.end * 30) return <OutroScene frame={frame} />;
  return <AssetsScene frame={frame} />;
};

// ============================================================================
// 场景 1 · 开场（0-22s · 0-660f）
// ============================================================================

const OpeningScene: React.FC<{ frame: number }> = ({ frame }) => {
  const local = frame;
  // 5 个 App 图标依次滑入 —— 用真实品牌 SVG（simple-icons），不再用字母占位
  const icons = [
    { file: "icons/brand/wecom-generic.svg", color: "#2E7CF6", name: "企业微信", at: 30 },
    { file: "icons/brand/whatsapp.svg", color: "#25D366", name: "WhatsApp", at: 110 },
    { file: "icons/brand/gmail.svg", color: "#EA4335", name: "邮箱", at: 190 },
    { file: "icons/ui/phone.svg", color: colors.phone, name: "电话", at: 270 },
    { file: "icons/ui/clipboard-list.svg", color: colors.inkDim, name: "自己的笔记", at: 350 },
  ];
  const iconSize = 130;
  const gap = 40;
  const totalW = icons.length * iconSize + (icons.length - 1) * gap;
  const startX = (W - totalW) / 2;
  const baseY = 360;

  const lines = [
    "一个做跨境物流的销售，",
    "**上班第一天就要装五个软件**。",
    "企业微信、WhatsApp、邮箱、",
    "**电话**，还有一本**自己的笔记**。",
    "而这五路消息背后，",
    "**很可能是同一个人**。",
  ];
  const lineStartAt = [0, 80, 180, 280, 380, 460];

  return (
    <Stage>
      <Glow x={W / 2} y={280} r={400} color={colors.accent2} opacity={0.12} />

      {/* App 图标条 */}
      {icons.map((it, i) => {
        const t = local - it.at;
        const x = startX + i * (iconSize + gap);
        const slideY = interpolate(t, [0, 26], [-40, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: Easing.out(Easing.cubic) });
        const op = interpolate(t, [0, 20], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
        return (
          <div key={i} style={{ position: "absolute", left: x, top: baseY, width: iconSize, height: iconSize, opacity: op, transform: `translateY(${slideY}px)` }}>
            <div style={{
              width: iconSize, height: iconSize, borderRadius: 28,
              background: it.color, boxShadow: "0 16px 40px rgba(0,0,0,0.5)",
              display: "flex", alignItems: "center", justifyContent: "center",
            }}>
              <Img
                src={staticFile(it.file)}
                style={{ width: iconSize * 0.52, height: iconSize * 0.52, filter: "brightness(0) invert(1)" }}
              />
            </div>
            <div style={{ position: "absolute", left: 0, right: 0, top: iconSize + 14, textAlign: "center", color: colors.inkSoft, fontSize: 20 }}>{it.name}</div>
          </div>
        );
      })}

      <Caption
        frame={local}
        lines={lines.map((t, i) => ({ text: t, emphasize: i === 5 ? "y" : "" }))}
        lineStartAt={lineStartAt}
        durationPerLine={80}
        y={780}
        size="hero"
      />

      <SectionMark frame={frame} start={S.opening.start} end={S.opening.end} label="开场 · 钩子" />
    </Stage>
  );
};

// ============================================================================
// 场景 2 · 人是散的（22-60s · 660-1800f）
// ============================================================================

const ScatteredScene: React.FC<{ frame: number }> = ({ frame }) => {
  const local = frame - S.scattered.start * 30;
  const total = (S.scattered.end - S.scattered.start) * 30;

  // 截图里的两个机位：全景 → 右侧「已绑定账号」特写
  // zoom=1 时焦点必须居中，否则会露出空白 —— 位移是相对容器中心的。
  const focusFull = { x: 0.5, y: 0.5, zoom: 1.0 };
  const focusPanel = { x: 0.806, y: 0.3, zoom: 2.15 };

  const lines = [
    "同一个人，",
    "在三个地方，**看起来是三个人**。",
    "所以系统先干一件事：",
    "把**企业**和**联系人**拆开。",
    "一个联系人挂多个身份，",
    "绑在一起，才是**一个完整的人**。",
    "**系统不替你猜归属。**",
  ];
  const lineStartAt = [0, 110, 280, 440, 620, 760, 920];

  return (
    <Stage>
      <SectionMark frame={frame} start={S.scattered.start} end={S.scattered.end} label="段一 · 人是散的" />

      {/* 真截图：整屏 → 推近到右侧「已绑定账号」——同一个人，三个渠道 */}
      <RealShot
        file="shots/03-david-channels.png"
        local={local}
        from={240}
        to={800}
        focusFrom={focusFull}
        focusTo={focusPanel}
        x={320}
        y={70}
        w={1280}
        h={720}
        glow="rgba(34,211,238,0.20)"
      />

      <Caption
        frame={local}
        lines={lines.map((t, i) => ({ text: t }))}
        lineStartAt={lineStartAt}
        durationPerLine={120}
        y={860}
        size="big"
      />
    </Stage>
  );
};

// ============================================================================
// 场景 3 · AI 助手（60-95s · 1800-2850f）
// ============================================================================

const AssistantScene: React.FC<{ frame: number }> = ({ frame }) => {
  const local = frame - S.assistant.start * 30;

  // 助手面板从右滑入
  const panelX = interpolate(local, [30, 80], [400, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: Easing.out(Easing.cubic) });
  const panelOp = interpolate(local, [30, 80], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });

  // 用户输入 → AI 回应 → 确认卡片
  const inputAppear = local > 180;
  const aiReplyAppear = local > 280;
  const cardAppear = local > 420;
  const cardScale = interpolate(local, [420, 460], [0.92, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: Easing.out(Easing.cubic) });

  const lines = [
    "系统里**我最喜欢**的一部分：",
    "**AI 助手**。",
    "你不用学任何指令，",
    "**直接打字说话就行**。",
    "凡是会真的发出去的操作，",
    "它会先弹一张**确认卡片**。",
    "**收件人不是 AI 填的**，",
    "是服务端从档案里查的。",
  ];
  const lineStartAt = [0, 80, 180, 280, 420, 540, 720, 840];

  return (
    <Stage>
      <SectionMark frame={frame} start={S.assistant.start} end={S.assistant.end} label="段二 · AI 助手" />

      {/* 左侧：CRM 联系人列表 mock */}
      <FadeIn frame={local} start={0}>
        <Card x={80} y={200} w={480} h={760}>
          <div style={{ padding: "20px 24px", borderBottom: `1px solid ${colors.divider}`, color: colors.ink, fontSize: 22, fontWeight: 700 }}>联系人</div>
          {["David Wang", "Maria Lopez", "陈海涛", "Takeshi Sato", "Anna Kim", "Lim Wei", "Priya Singh"].map((name, i) => (
            <Row key={i} x={0} y={64 + i * 60} w={480}
              left={<Avatar inline size={40} bg={[colors.wecom, colors.whatsapp, colors.email, colors.phone, colors.accent2, colors.accent, colors.success][i]} label={name[0]} />}
              center={<span style={{ fontSize: 20 }}>{name}</span>}
              right={<span style={{ fontSize: 14, color: colors.inkSoft }}>{["企业微信", "WhatsApp", "邮箱", "电话", "WhatsApp", "邮件", "电话"][i]}</span>}
            />
          ))}
        </Card>
      </FadeIn>

      {/* 中间：消息时间线 mock */}
      <FadeIn frame={local} start={20}>
        <Card x={600} y={200} w={700} h={760}>
          <div style={{ padding: "20px 24px", borderBottom: `1px solid ${colors.divider}`, display: "flex", alignItems: "center" }}>
            <Avatar inline size={48} bg={colors.wecom} label="D" />
            <div style={{ marginLeft: 14, color: colors.ink }}>
              <div style={{ fontSize: 22, fontWeight: 700 }}>David Wang</div>
              <div style={{ fontSize: 14, color: colors.inkSoft, marginTop: 4 }}>最后联系：昨天 · 企业微信</div>
            </div>
          </div>
          <Bubble x={30} y={110} text="老板，HK 那批货价格确认了吗？" bg={colors.bgPanel2} />
          <Bubble x={700 - 320 - 30} y={200} text="USD 1250/20GP，有效期 7 天。" bg={colors.wecom} fg="#fff" tail="right" w={320} />
          <Bubble x={30} y={290} text="客户要走东南亚线，要不要调整？" bg={colors.bgPanel2} />
        </Card>
      </FadeIn>

      {/* 右侧：助手面板 */}
      <div style={{
        position: "absolute", right: 80, top: 200, width: 460, height: 760,
        transform: `translateX(${panelX}px)`, opacity: panelOp,
        transition: "none",
      }}>
        <Card x={0} y={0} w={460} h={760}>
          <div style={{ padding: "20px 24px", borderBottom: `1px solid ${colors.divider}`, color: colors.accent, fontSize: 22, fontWeight: 800, fontFamily: font.display }}>
            ✦ AI 助手
          </div>
          {/* 用户输入 */}
          {inputAppear && (
            <FadeIn frame={local} start={180}>
              <Bubble x={20} y={80} text="给 David 发封邮件，问他那批货什么时候确认。" bg={colors.accent} fg={colors.bgDeep} w={420} />
            </FadeIn>
          )}
          {/* AI 回应 */}
          {aiReplyAppear && (
            <FadeIn frame={local} start={280}>
              <Bubble x={20} y={220} text="已为你拟好草稿，请确认收件人和正文：" bg={colors.bgPanel2} w={420} />
            </FadeIn>
          )}
          {/* 确认卡片 */}
          {cardAppear && (
            <div style={{
              position: "absolute", left: 20, top: 380, width: 480, padding: "24px 28px",
              background: colors.bgPanel2, border: `2px solid ${colors.accent}`, borderRadius: 18,
              transform: `scale(${cardScale})`, transformOrigin: "top left",
              boxShadow: "0 20px 50px rgba(251,191,36,0.25)",
            }}>
              <div style={{ fontSize: 16, color: colors.inkSoft, marginBottom: 8 }}>收件人（来自联系人档案）</div>
              <div style={{ fontSize: 26, color: colors.ink, fontWeight: 700, marginBottom: 16 }}>david@company.com</div>
              <div style={{ fontSize: 16, color: colors.inkSoft, marginBottom: 8 }}>正文</div>
              <div style={{ fontSize: 18, color: colors.ink, lineHeight: 1.5 }}>
                David，HK 那批货本周能确认吗？价格还按昨天那个报价走。
              </div>
              <div style={{ display: "flex", gap: 12, marginTop: 20 }}>
                <div style={{ flex: 1, padding: "12px 0", background: colors.accent, color: colors.bgDeep, borderRadius: 10, textAlign: "center", fontWeight: 700 }}>✓ 确认发送</div>
                <div style={{ flex: 1, padding: "12px 0", background: colors.bgPanel, color: colors.ink, borderRadius: 10, textAlign: "center", fontWeight: 700 }}>编辑</div>
              </div>
            </div>
          )}
        </Card>
      </div>

      <Caption
        frame={local}
        lines={lines.map((t) => ({ text: t }))}
        lineStartAt={lineStartAt}
        durationPerLine={120}
        y={1000 - 200}
        size="big"
        maxWidth={1700}
      />
    </Stage>
  );
};

// ============================================================================
// 场景 4 · 企微不代发（95-135s · 2850-4050f）
// ============================================================================

const WeComScene: React.FC<{ frame: number }> = ({ frame }) => {
  const local = frame - S.wecom.start * 30;

  // 阶段：① 草稿(0-300) ② 复制按钮按下(300-500) ③ 唤起企微窗口(500-800) ④ 销售手按(800-1100)
  const draftY = interpolate(local, [0, 30], [60, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: Easing.out(Easing.cubic) });
  const draftOp = interpolate(local, [0, 30], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });

  const copyPressed = local > 280;
  const dockAppear = local > 480;
  const dockOp = interpolate(local, [480, 520], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const windowAppear = local > 600;
  const windowY = interpolate(local, [600, 660], [80, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: Easing.out(Easing.cubic) });
  const handAppear = local > 800;
  const pressFlash = local > 850 && local < 920;
  const pressScale = pressFlash ? 1.08 : 1;

  const lines = [
    "接下来是**最重**的一块：",
    "**企业微信**。",
    "能同步客户联系、跟进关系、",
    "**聊天记录**也能合规地进来。",
    "但**有一条线**我们**故意**没跨过去。",
    "AI 写草稿，",
    "你**复制**、**唤起**企业微信、",
    "**自己按发送**。",
    "**这不是技术做不到**，",
    "**是选择不做**。",
  ];
  const lineStartAt = [0, 80, 180, 300, 420, 580, 700, 820, 940, 1040];

  return (
    <Stage>
      <SectionMark frame={frame} start={S.wecom.start} end={S.wecom.end} label="段三 · 企微不代发" />

      {/* AI 草稿框 */}
      <div style={{
        position: "absolute", left: 120, top: 280,
        width: 740, padding: "30px 36px",
        background: colors.bgPanel, border: `1px solid ${colors.divider}`, borderRadius: 20,
        transform: `translateY(${draftY}px)`, opacity: draftOp,
        boxShadow: "0 20px 50px rgba(0,0,0,0.4)",
      }}>
        <div style={{ color: colors.accent, fontSize: 18, fontWeight: 700, marginBottom: 12 }}>✦ AI 草稿</div>
        <div style={{ color: colors.ink, fontSize: 24, lineHeight: 1.6 }}>
          David，HK 那批货本周能确认吗？价格按昨天那个报价走。
        </div>
        <div style={{ marginTop: 24, display: "flex", gap: 12 }}>
          <div style={{
            padding: "14px 24px",
            background: copyPressed ? colors.success : colors.accent,
            color: colors.bgDeep,
            borderRadius: 10, fontWeight: 700, fontSize: 18,
            transform: copyPressed ? `scale(${pressScale})` : "scale(1)",
            transition: "all 0.18s",
          }}>
            {copyPressed ? "✓ 已复制" : "一键复制话术"}
          </div>
          <div style={{
            padding: "14px 24px",
            background: colors.bgPanel2,
            color: colors.ink,
            borderRadius: 10, fontWeight: 700, fontSize: 18,
            opacity: dockAppear ? 1 : 0.5,
          }}>
            唤起企业微信 →
          </div>
        </div>
      </div>

      {/* 企微窗口（唤起后） */}
      {windowAppear && (
        <div style={{
          position: "absolute", right: 80, top: 200,
          width: 720, height: 600,
          transform: `translateY(${windowY}px)`,
          background: "#fff", borderRadius: 18, overflow: "hidden",
          boxShadow: "0 30px 80px rgba(0,0,0,0.5)",
        }}>
          {/* 标题栏 */}
          <div style={{ height: 56, background: colors.wecom, display: "flex", alignItems: "center", padding: "0 20px", color: "#fff", fontSize: 18, fontWeight: 700 }}>
            <span style={{ display: "inline-block", width: 12, height: 12, borderRadius: 6, background: "#fff", marginRight: 12 }} />
            企业微信 · David Wang
          </div>
          {/* 消息区 */}
          <div style={{ padding: 24, background: "#f5f5f5", height: 420, position: "relative" }}>
            <Bubble x={20} y={20} text="老板，HK 那批货价格确认了吗？" bg="#fff" fg="#222" />
            <Bubble x={720 - 360 - 20} y={100} text="USD 1250/20GP，有效 7 天" bg={colors.wecom} fg="#fff" tail="right" w={360} />
            <Bubble x={20} y={180} text="客户要走东南亚线" bg="#fff" fg="#222" />
            {/* 草稿占位 */}
            <div style={{
              position: "absolute", right: 20, bottom: 20,
              padding: "16px 20px",
              background: "#fff3bf", border: "2px dashed #fbbf24",
              borderRadius: 12, fontSize: 18, color: "#222",
              maxWidth: 380,
            }}>
              David，HK 那批货本周能确认吗？价格按昨天那个报价走。
            </div>
          </div>
          {/* 输入栏 + 发送按钮 */}
          <div style={{ height: 124, background: "#ededed", display: "flex", alignItems: "center", padding: "0 20px" }}>
            <div style={{ flex: 1, height: 60, background: "#fff", borderRadius: 8 }} />
            <div style={{
              marginLeft: 12, padding: "0 32px", height: 60,
              display: "flex", alignItems: "center",
              background: colors.wecom, color: "#fff",
              borderRadius: 8, fontWeight: 700, fontSize: 20,
              transform: `scale(${pressFlash ? 0.95 : 1})`,
            }}>
              发送 ↑
            </div>
          </div>
        </div>
      )}

      {/* macOS dock */}
      <div style={{ position: "absolute", left: 0, right: 0, bottom: 0, opacity: dockOp }}>
        <Dock y={H - 90} />
      </div>

      <Caption
        frame={local}
        lines={lines.map((t) => ({ text: t }))}
        lineStartAt={lineStartAt}
        durationPerLine={120}
        y={H - 240}
        size="big"
      />
    </Stage>
  );
};

// ============================================================================
// 场景 5 · 后台 AI（135-165s · 4050-4950f）
// ============================================================================

const MemoryScene: React.FC<{ frame: number }> = ({ frame }) => {
  const local = frame - S.memory.start * 30;

  const lines = [
    "AI 也不只是一个聊天框。",
    "它会在后台慢慢提炼**事实**：",
    "走东南亚线、",
    "对价格敏感、",
    "**习惯英文沟通**。",
    "**每条都要有证据**，",
    "才会写进档案。",
    "没新东西，",
    "**一个字节都不写**。",
  ];
  const lineStartAt = [0, 100, 200, 280, 340, 460, 600, 740, 800];

  return (
    <Stage>
      <SectionMark frame={frame} start={S.memory.start} end={S.memory.end} label="段四 · 后台 AI" />

      {/* 真截图：AI 画像 + AI 标签（右侧详情栏推近） */}
      <RealShot
        file="shots/04-david-info.png"
        local={local}
        from={60}
        to={680}
        focusFrom={{ x: 0.5, y: 0.5, zoom: 1.0 }}
        focusTo={{ x: 0.806, y: 0.33, zoom: 2.0 }}
        x={320}
        y={70}
        w={1280}
        h={720}
        glow="rgba(251,191,36,0.22)"
      />

      <Caption
        frame={local}
        lines={lines.map((t) => ({ text: t }))}
        lineStartAt={lineStartAt}
        durationPerLine={100}
        y={H - 200}
        size="big"
      />
    </Stage>
  );
};

// ============================================================================
// 场景 6 · 快剪（165-180s · 4950-5400f）
// ============================================================================

const MontageScene: React.FC<{ frame: number }> = ({ frame }) => {
  const local = frame - S.montage.start * 30;
  // fx / fy / fz = 各自要推近的焦点与倍数（整图缩到 1066 宽时字太小，必须推近）
  const cards = [
    { title: "多渠道身份 · 一屏可查", color: colors.accent2, file: "shots/05-chen-channels.png", fx: 0.8, fy: 0.33, fz: 2.2 },
    { title: "AI 画像 · 逐条有据", color: colors.accent, file: "shots/05b-chen-info.png", fx: 0.8, fy: 0.33, fz: 2.2 },
    { title: "会话列表 · 置顶 · 拖拽", color: colors.whatsapp, file: "shots/02-david-thread.png", fx: 0.5, fy: 0.42, fz: 1.7 },
    { title: "统一入口 · 全渠道收发", color: colors.wecom, file: "shots/01-home-list.png", fx: 0.5, fy: 0.45, fz: 1.6 },
  ];
  const cardDur = 100; // 每张 100f ≈ 3.3s
  const idx = Math.floor(local / cardDur);
  const cardLocal = local - idx * cardDur;
  const cardOp = interpolate(cardLocal, [0, 10, 90, 100], [0, 1, 1, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });

  return (
    <Stage>
      <SectionMark frame={frame} start={S.montage.start} end={S.montage.end} label="快剪" />
      {cards.map((c, i) => i === idx && (
        <div key={i} style={{
          position: "absolute", left: 427, top: 168,
          width: 1066, height: 600,
          opacity: cardOp,
        }}>
          <RealShot
            file={c.file}
            local={cardLocal}
            from={0}
            to={cardDur}
            focusFrom={{ x: c.fx, y: c.fy, zoom: c.fz * 1.05 }}
            focusTo={{ x: c.fx, y: c.fy, zoom: c.fz }}
            x={0}
            y={0}
            w={1066}
            h={600}
            glow="rgba(255,255,255,0.12)"
          />
          <div style={{ position: "absolute", left: 2, top: -58, color: c.color, fontSize: 42, fontWeight: 800, fontFamily: font.display }}>
            {String(i + 1).padStart(2, "0")} · {c.title}
          </div>
        </div>
      ))}

      <Caption
        frame={local}
        lines={[{ text: "还有这些**不起眼**的东西——" }]}
        lineStartAt={[0]}
        durationPerLine={500}
        y={H - 200}
        size="big"
      />
    </Stage>
  );
};

// ============================================================================
// 场景 7 · 结尾（180-195s · 5400-5850f）
// ============================================================================

const OutroScene: React.FC<{ frame: number }> = ({ frame }) => {
  const local = frame - S.outro.start * 30;
  const iconOp = interpolate(local, [0, 30], [0, 0.4], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const iconY = interpolate(local, [0, 30], [-30, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });

  return (
    <Stage>
      <SectionMark frame={frame} start={S.outro.start} end={S.outro.end} label="结尾" />

      {/* 缩小版 App 图标条 */}
      <div style={{ position: "absolute", left: 0, right: 0, top: 100, opacity: iconOp, transform: `translateY(${iconY}px)` }}>
        <AppStrip y={0} iconSize={70} gap={20} />
      </div>

      {/* 金句 */}
      <StaticCaption
        text="**信息不会丢。**"
        frame={local}
        appearAt={120}
        x={W / 2}
        y={H / 2 - 40}
        size="hero"
      />
      <StaticCaption
        text="**人，就还是那个人。**"
        frame={local}
        appearAt={260}
        x={W / 2}
        y={H / 2 + 60}
        size="hero"
      />
    </Stage>
  );
};

// ============================================================================
// 场景 8 · 素材清单（195-216s · 5850-6480f）
// ============================================================================

const AssetsScene: React.FC<{ frame: number }> = ({ frame }) => {
  const local = frame - S.assets.start * 30;
  const items = [
    { scene: "真实截图", need: "9 张 —— 本机跑起系统后由无头 Chrome 截取：联系人列表 / 渠道身份 / AI 画像 / 通讯录" },
    { scene: "品牌图标", need: "simple-icons（CC0，可商用免署名）：微信 · WhatsApp · Gmail · Telegram · Signal · X" },
    { scene: "UI 图标", need: "lucide（ISC，可商用免署名）：30 个线性图标" },
    { scene: "配音", need: "macOS 内置 say（Tingting 等 9 套中文语音）—— 草稿音，换真人只需替换文件" },
    { scene: "BGM", need: "纯合成（A 小调不解决和弦 + 0.07Hz 呼吸 LFO）—— 零版权风险" },
    { scene: "音效", need: "macOS 系统音效库：Ping / Tink / Pop / Glass / Blow" },
    { scene: "代码重绘", need: "段二 AI 助手、段三 企业微信 —— 前端暂无对应界面，画面为示意" },
  ];
  const headerAppear = local > 0;
  const listStart = 80;
  const rowH = 64;

  return (
    <Stage>
      <SectionMark frame={frame} start={S.assets.start} end={S.assets.end} label="素材来源" />

      <StaticCaption
        text="**本片素材来源**"
        frame={local}
        appearAt={0}
        x={120}
        y={150}
        align="left"
        size="big"
      />

      <div style={{
        position: "absolute", left: 120, right: 120, top: 280,
        fontFamily: font.sans,
      }}>
        {items.map((it, i) => {
          const t = local - (listStart + i * 20);
          const op = interpolate(t, [0, 20], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
          const x = interpolate(t, [0, 24], [40, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: Easing.out(Easing.cubic) });
          return (
            <div key={i} style={{
              display: "flex", gap: 24, alignItems: "center",
              padding: "16px 24px",
              borderTop: i === 0 ? "none" : `1px solid ${colors.divider}`,
              opacity: op, transform: `translateX(${x}px)`,
            }}>
              <div style={{
                flex: "0 0 200px",
                padding: "6px 14px",
                background: "rgba(251,191,36,0.15)",
                color: colors.accent,
                borderRadius: 999,
                fontSize: 18, fontWeight: 700, textAlign: "center",
              }}>
                {it.scene}
              </div>
              <div style={{
                flex: 1,
                color: colors.ink, fontSize: 24,
              }}>
                {it.need}
              </div>
            </div>
          );
        })}
      </div>

      <div style={{
        position: "absolute", left: 120, right: 120, bottom: 100,
        color: colors.inkSoft, fontSize: 22, fontFamily: font.sans,
        opacity: interpolate(local, [items.length * 20 + listStart, items.length * 20 + listStart + 60], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" }),
      }}>
        企业微信图标为自绘示意、非官方 logo；段二与段三为示意画面。对外投放前请替换为官方素材与实拍。
      </div>
    </Stage>
  );
};