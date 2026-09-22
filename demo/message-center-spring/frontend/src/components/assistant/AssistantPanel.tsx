import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Empty, Flex, Input, Space, Tag, Typography, theme } from 'antd';
import { ExclamationCircleOutlined, SendOutlined } from '@ant-design/icons';
import type { AssistantProposalChange } from '../../api/types';
import { AssistantCodes, useAssistant, type AssistantChatItem } from './useAssistant';

const { Text, Paragraph } = Typography;

const EXAMPLES = [
  '帮我记一下明天下午三点和张总确认报价',
  '把和张总确认报价标记完成',
];

/**
 * 助手会话面板（`Drawer` 的内容）。
 *
 * <h2>渲染的依据只有一个：`kind`</h2>
 * 面板不去猜「这一轮大概做成了什么」，而是严格按服务端给的终点分支。这样「已执行」
 * 就永远只可能来自服务端的 `EXECUTED` —— 前端没有第二条能产出成功的路径。
 *
 * <h2>做不成的三件事都必须说出来</h2>
 * 模型不可用（503）、参数不合法（400）、引用的待办已消失（200 里的 `ERROR`）：
 * 三种都不是「再试一次可能就好了」，但用户需要的都是同一件事 ——
 * 知道**这次没成**，以及**为什么**。所以它们在对话里各留一条失败消息，而不是一个 3 秒就消失的提示。
 *
 * <h2>打开时回放历史</h2>
 * 对话正文已经落库（`assistant_conversation_messages`），面板第一次打开时按当前会话号拉回一次，
 * 于是刷新页面不再等于失忆。回放只还原**消息与它的终点**，不还原确认卡片（见 `itemOfHistory`）。
 */
export function AssistantPanel() {
  const {
    items, busy, pending, unavailable,
    send, confirm, cancel, retry, loadHistory, startNewConversation,
  } = useAssistant();
  const [draft, setDraft] = useState('');
  const bottomRef = useRef<HTMLDivElement>(null);
  const { token } = theme.useToken();

  // 面板第一次打开时回放一次。antd Drawer 在首次 open 之前不渲染子元素，
  // 所以这个 effect 天然只在「用户真的打开了面板」之后跑；`loadHistory` 内部另有一次性保护。
  useEffect(() => {
    void loadHistory();
  }, [loadHistory]);

  // 新一轮消息进来后把视口带到最新一条：对话是「往下长」的，不跟着走就得手动滚。
  useEffect(() => {
    const element = bottomRef.current;
    if (element && typeof element.scrollIntoView === 'function') {
      element.scrollIntoView({ block: 'end' });
    }
  }, [items.length, busy]);

  // 开关没打开时输入框直接禁用：让用户能打字再告诉他「功能没开」，是在浪费他的时间。
  const disabled = unavailable?.code === AssistantCodes.DISABLED;

  const submit = () => {
    const text = draft.trim();
    if (!text || busy || disabled) return;
    setDraft('');
    send(text);
  };

  return (
    <Flex vertical style={{ height: '100%' }} data-testid="assistant-panel">
      <Flex
        justify="flex-end"
        style={{ padding: '6px 16px', borderBottom: `1px solid ${token.colorBorderSecondary}` }}
      >
        <Button
          size="small"
          type="text"
          onClick={startNewConversation}
          // 空的对话没什么可清；请求在飞时切换会把结果写进错误的上下文。
          disabled={busy || items.length === 0}
        >
          新会话
        </Button>
      </Flex>

      <Flex vertical gap={12} style={{ flex: 1, overflowY: 'auto', padding: 16 }}>
        {unavailable && (
          <Alert
            type={disabled ? 'warning' : 'error'}
            showIcon
            message={disabled ? 'AI 助手未开启' : '模型服务暂时不可用'}
            // 直接把服务端的话放上来，而不是改写成一句「操作失败」——
            // 「功能没开」和「模型挂了」需要的是两种完全不同的下一步动作。
            description={disabled
              ? unavailable.message
              : `${unavailable.message}。稍后再发一次通常就好了。`}
          />
        )}

        {items.length === 0 && !unavailable && (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={<Text type="secondary">可以直接用一句话交代事情，比如：</Text>}
          >
            <Flex vertical gap={6} align="center">
              {EXAMPLES.map((example) => (
                <Text key={example} type="secondary" code>{example}</Text>
              ))}
            </Flex>
          </Empty>
        )}

        {items.map((item, index) => (
          <ChatRow
            key={item.id}
            item={item}
            // 只有最后一条才配「重试」：更早的失败按钮按下的仍是「最新一次请求」，
            // 那会让人以为重试的是这一条。
            isLast={index === items.length - 1}
            busy={busy}
            actionable={!!pending && pending.pendingActionId === item.proposal?.pendingActionId}
            onConfirm={confirm}
            onCancel={cancel}
            onRetry={retry}
          />
        ))}
        <div ref={bottomRef} />
      </Flex>

      <Flex
        vertical
        gap={8}
        style={{ padding: 16, borderTop: `1px solid ${token.colorBorderSecondary}` }}
      >
        {pending && (
          <Text type="warning">
            有一条操作等着你确认，确认前不会执行。
          </Text>
        )}
        <Input.TextArea
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder={disabled ? '助手未开启' : '说一句话，例如：把和张总确认报价标记完成'}
          autoSize={{ minRows: 1, maxRows: 4 }}
          maxLength={2000}
          disabled={disabled}
          aria-label="给助手的话"
          onPressEnter={(event) => {
            if (!event.shiftKey) {
              event.preventDefault();
              submit();
            }
          }}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          aria-label="发送给助手"
          loading={busy}
          disabled={disabled || !draft.trim()}
          onClick={submit}
        >
          发送
        </Button>
      </Flex>
    </Flex>
  );
}

type ChatRowProps = {
  item: AssistantChatItem;
  isLast: boolean;
  busy: boolean;
  /** 这条记录上的确认卡片是不是「当前仍然待确认」的那一条。 */
  actionable: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  onRetry: () => void;
};

function ChatRow({ item, isLast, busy, actionable, onConfirm, onCancel, onRetry }: ChatRowProps) {
  const { token } = theme.useToken();

  if (item.role === 'user') {
    return (
      <Flex justify="flex-end">
        <Bubble background={token.colorPrimaryBg}>{item.text}</Bubble>
      </Flex>
    );
  }

  if (item.kind === 'CONFIRMATION_REQUIRED' && item.proposal && actionable) {
    return (
      <Card
        size="small"
        style={{ borderColor: token.colorWarningBorder, background: token.colorWarningBg }}
      >
        <Flex vertical gap={10}>
          <Space size={6}>
            <ExclamationCircleOutlined style={{ color: token.colorWarning }} />
            <Text strong>需要你确认</Text>
            <Tag color="warning">尚未执行</Tag>
          </Space>
          {/* 正文可能是长文本（一封邮件草稿）：保留换行，不许被压成一行。 */}
          <Paragraph style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{item.proposal.summary}</Paragraph>
          {item.proposal.changes && item.proposal.changes.length > 0 && (
            <ChangeList changes={item.proposal.changes} />
          )}
          <Space>
            <Button type="primary" onClick={onConfirm} loading={busy}>确认</Button>
            <Button onClick={onCancel} disabled={busy}>取消</Button>
            {busy && <Text type="secondary">正在执行，请稍候…</Text>}
          </Space>
        </Flex>
      </Card>
    );
  }

  if (item.kind === 'ERROR') {
    return (
      <Flex vertical gap={6} align="flex-start">
        <Space size={6}>
          <Tag color="error">失败</Tag>
          {item.errorCode && <Text type="secondary" style={{ fontSize: 12 }}>{item.errorCode}</Text>}
        </Space>
        <Bubble background={token.colorErrorBg} color={token.colorErrorText}>{item.text}</Bubble>
        {isLast && item.retryable && (
          <Button size="small" onClick={onRetry} disabled={busy}>重试</Button>
        )}
      </Flex>
    );
  }

  return (
    <Flex vertical gap={6} align="flex-start">
      {item.kind === 'EXECUTED' && <Tag color="success">已执行</Tag>}
      {item.kind === 'QUESTION' && <Tag color="processing">需要补充信息</Tag>}
      {/*
        只标注**来自历史回放**的那一条：回放不带 `proposal`（见 itemOfHistory）。
        本会话内刚处理完的那一条同样有 kind 却已无卡片，但它的结果就显示在下面，
        再打一个标签会和结果自相矛盾。
      */}
      {item.kind === 'CONFIRMATION_REQUIRED' && !item.proposal && <Tag>当时待确认</Tag>}
      <Bubble background={token.colorFillQuaternary}>{item.text}</Bubble>
    </Flex>
  );
}

/**
 * 卡片上的「改前 → 改后」。
 *
 * `before` 为空时写「当前未知」而不是留白：留白会被读成「没有变化」，而服务端的意思
 * 恰恰相反 —— 它没拿到「改前」的证据，所以不敢填。这个区别是这张卡片能不能被信任的关键：
 * 一个编出来的「改前」会被用户当成事实去核对，那比不显示更糟。
 *
 * 用空值判断而不是 `??`：后端已保证不传空串（blankToNull），但万一传来，空串渲染成一个
 * 空格和「未知」在视觉上是两回事，这里统一按未知处理。
 */
function ChangeList({ changes }: { changes: AssistantProposalChange[] }) {
  return (
    <Flex vertical gap={2} data-testid="assistant-proposal-changes">
      {changes.map((change) => (
        <Flex key={change.field} gap={8} align="baseline" wrap>
          <Text type="secondary" style={{ minWidth: 40 }}>{change.label}</Text>
          <Text type="secondary">{change.before ? change.before : '（当前未知）'}</Text>
          <Text type="secondary">→</Text>
          <Text strong>{change.after}</Text>
        </Flex>
      ))}
    </Flex>
  );
}

function Bubble({
  children,
  background,
  color,
}: {
  children: React.ReactNode;
  background: string;
  color?: string;
}) {
  return (
    <div
      style={{
        maxWidth: '85%',
        padding: '8px 12px',
        borderRadius: 8,
        background,
        color,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
      }}
    >
      {children}
    </div>
  );
}
