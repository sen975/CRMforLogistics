import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Empty, Flex, Input, Select, Space, Tag, Typography } from 'antd';
import {
  CloseOutlined, DeleteOutlined, ExclamationCircleOutlined, LoadingOutlined, PictureOutlined, SendOutlined,
} from '@ant-design/icons';
import type { AssistantProposalChange, TemplateMediaFormat } from '../../api/types';
import { fetchTemplateMediaUpload, uploadTemplateMedia } from '../../api/endpoints';
import { recoverTemplateMediaUpload } from '../templates/templateMediaUpload';
import { AssistantCodes, useAssistant, type AssistantChatItem } from './useAssistant';

const { Text, Paragraph } = Typography;

const EXAMPLES = [
  '帮我记一下明天下午三点和张总确认报价',
  '把和张总确认报价标记完成',
  '用这张图建一个开发客户用的 WhatsApp 模板',
];

/**
 * 图片上限，与 `WhatsAppTemplateValidator` 的图片规格同源（5MB、jpeg/png）。
 *
 * 前端先拦一次**不是为了替代服务端**，而是为了让「这张图太大」在选中那一刻就说出来，
 * 而不是等一次完整上传跑完再收到一个 400。
 */
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const ACCEPTED_IMAGE_TYPES = ['image/jpeg', 'image/png'] as const;

/**
 * 输入框上方那一条附件的三种状态。
 *
 * `assetId` 只在 `ready` 分支存在 —— 用联合类型而不是「可空的 id」，是为了让
 * 「上传中也能拿到 id」在类型上就写不出来。
 */
type PanelAttachment = { name: string; previewUrl: string; format: TemplateMediaFormat; error?: string } & (
  { status: 'uploading' } | { status: 'failed' } | { status: 'ready'; assetId: string }
);

function attachmentRequestId(): string {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `assistant-media-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * 从失败的请求里取出服务端那句话。
 *
 * <p>这是**第 5 份**同样的实现（另有 `TemplatesPage`、`BroadcastsPage` 与两个 wecom 面板）。
 * 本轮没有顺手收敛：那会把这个改动扩到四个互不相关的文件上，收敛属独立批次。
 * 记在这里，免得下一个人以为它是个共享工具而去找它的出处。
 */
function attachmentErrorMessage(error: unknown): string {
  return (error as { response?: { data?: { message?: string } } }).response?.data?.message
    ?? '图片上传失败，请稍后重试';
}

/**
 * 助手会话面板（`Drawer` 的内容）。
 *
 * <h2>渲染的依据只有一个：`kind`</h2>
 * 面板不去猜「这一轮大概做成了什么」，而是严格按服务端给的终点分支。这样「已执行」
 * 就永远只可能来自服务端的 `EXECUTED` —— 前端没有第二条能产出成功的路径。
 *
 * <h2>三件做不成的事都必须说出来</h2>
 * 模型不可用（503）、参数不合法（400）、引用的待办已消失（200 里的 `ERROR`）：
 * 三种都不是「再试一次可能就好了」，但用户需要的都是同一件事 ——
 * 知道**这次没成**，以及**为什么**。所以它们在对话里各留一条失败消息，而不是一个 3 秒就消失的提示。
 *
 * <p><b>第四件：助手看不见前面了。</b> 它比前三种更隐蔽 —— 上下文被裁剪之后，
 * 模型不但失去了那段对话，还**不知道自己失去了**，于是会拿着断掉的开头照常作答。
 * 所以服务端把裁剪规模放进响应，面板在输入框上方把它说出来。宁可提示显得多余，
 * 也不要让用户在毫无察觉的情况下收到一个依据不足的回答。
 *
 * <p><b>第五件：打开时接不上上次。</b> 面板打开的第一件事是回放，而它失败时以前只留下一行
 * `console.warn` —— 于是「助手没开启」与「他刚点了新会话」在界面上**完全一样**，都是一片空白，
 * 而这两种情况的下一步动作正相反。现在前者走顶部的「AI 助手未开启」（并禁用输入框），
 * 其余失败给一条独立提示：**上面这段对话不完整**。它不写进对话 —— 它不属于任何一轮对话。
 *
 * <p><b>第六件：回答是长出来的，不是一次性出现的。</b> 这一轮的记录先以空正文出现，
 * 片段到达就往它身上长，最后的结论整条覆盖它（见 `useAssistant` 的「先占位、后覆盖」）。
 * 面板在这里的职责只有一个：**还在推的时候不许显示任何结论标签** ——
 * 成功 / 失败 / 待确认此刻都还不知道，先贴一个标签出去就是在替服务端下结论。
 *
 * <h2>打开时回放历史</h2>
 * 对话正文已经落库（`assistant_conversation_messages`），面板第一次打开时按当前会话号拉回一次，
 * 于是刷新页面不再等于失忆。回放只还原**消息与它的终点**，不还原确认卡片（见 `itemOfHistory`）。
 */
export function AssistantPanel() {
  const {
    items, busy, pending, unavailable, trimmedHistory, compactedHistory, historyError,
    conversationId, conversations, send, confirm, cancel, retry, loadHistory, startNewConversation,
    openConversation, deleteConversation,
  } = useAssistant();
  const [draft, setDraft] = useState('');
  const [conversationPickerOpen, setConversationPickerOpen] = useState(false);
  const [attachment, setAttachment] = useState<PanelAttachment | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  /**
   * 上一次上传的中断句柄。用户换一张图时上一张还在飞 —— 不中断的话它会回来覆盖新的那张，
   * 而屏幕上显示的将是**一个已经不在手上的素材 id**。
   */
  const uploadAbortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  // 面板第一次打开时回放一次。antd Drawer 在首次 open 之前不渲染子元素，
  // 所以这个 effect 天然只在「用户真的打开了面板」之后跑；`loadHistory` 内部另有一次性保护。
  useEffect(() => {
    void loadHistory();
  }, [loadHistory]);

  // 逐字那一轮的长度。把它放进下面那个 effect 的依赖里，视口才会跟着正文一起长 ——
  // 只在「多了一条消息」时滚的话，长回答会边写边从屏幕上退出去，用户得自己追。
  const streamingLength = items.find((item) => item.streaming)?.text.length ?? 0;

  // 新一轮消息进来后把视口带到最新一条：对话是「往下长」的，不跟着走就得手动滚。
  useEffect(() => {
    const element = bottomRef.current;
    if (element && typeof element.scrollIntoView === 'function') {
      element.scrollIntoView({ block: 'end' });
    }
  }, [items.length, busy, streamingLength]);

  // 开关没打开时输入框直接禁用：让用户能打字再告诉他「功能没开」，是在浪费他的时间。
  const disabled = unavailable?.code === AssistantCodes.DISABLED;

  useEffect(() => {
    // 切换或删除当前会话时关闭已经打开的浮层，避免用户继续点到旧选项快照。
    setConversationPickerOpen(false);
  }, [conversationId]);

  /**
   * 选中一张图之后的完整动作：先本地拦，再上传。
   *
   * <h2>为什么在「选中」时上传，而不是在「发送」时</h2>
   * 上传要几秒到几十秒。放进发送流程的话，这段时间在屏幕上长得像「助手正在思考」——
   * 而它其实一个字都还没发出去。放在这里，等待被用户打字的时间掩掉大半，
   * 而且失败有一块固定的地方能显示和重试。
   *
   * <h2>为什么用 recoverTemplateMediaUpload 而不是直接调上传接口</h2>
   * 上传可能是**异步**的：接口返回时素材未必已经 `UPLOADED`（可能是 `PROCESSING`）。
   * 服务层的 `prepareMedia` 只认 `UPLOADED`，所以拿一个还没传完的 id 去建模板必然失败。
   * 那个函数负责「上传 → 回查 → 等到可用或确认失败」，是这条链上既有的一段，不重写。
   */
  const attach = useCallback(async (file: File) => {
    if (!(ACCEPTED_IMAGE_TYPES as readonly string[]).includes(file.type)) {
      setAttachment({
        status: 'failed',
        name: file.name,
        previewUrl: '',
        format: 'IMAGE',
        error: '只能上传 JPEG 或 PNG 图片',
      });
      return;
    }
    if (file.size > MAX_IMAGE_BYTES) {
      setAttachment({
        status: 'failed',
        name: file.name,
        previewUrl: '',
        format: 'IMAGE',
        error: `图片不能超过 ${MAX_IMAGE_BYTES / 1024 / 1024}MB`,
      });
      return;
    }

    uploadAbortRef.current?.abort();
    const controller = new AbortController();
    uploadAbortRef.current = controller;
    // 本地预览：上传回来之前也要能看见自己选的是哪一张。
    const localPreview = typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function'
      ? URL.createObjectURL(file)
      : '';
    setAttachment({ status: 'uploading', name: file.name, previewUrl: localPreview, format: 'IMAGE' });

    const clientRequestId = attachmentRequestId();
    try {
      const asset = await recoverTemplateMediaUpload({
        clientRequestId,
        signal: controller.signal,
        upload: (stableId, signal) => uploadTemplateMedia('IMAGE', file, stableId, signal),
        find: (stableId, signal) => fetchTemplateMediaUpload(stableId, signal),
      });
      if (controller.signal.aborted) return;
      setAttachment({
        status: 'ready',
        name: file.name,
        assetId: asset.id,
        format: 'IMAGE',
        previewUrl: asset.providerUrl ?? localPreview,
      });
    } catch (error) {
      if (controller.signal.aborted) return;
      setAttachment({
        status: 'failed',
        name: file.name,
        previewUrl: localPreview,
        format: 'IMAGE',
        error: attachmentErrorMessage(error),
      });
    }
  }, []);

  const removeAttachment = useCallback(() => {
    uploadAbortRef.current?.abort();
    uploadAbortRef.current = null;
    setAttachment(null);
  }, []);

  // 换图或移除时释放上一张的本地预览地址。不释放的话，来回试几张就会一直占着内存。
  useEffect(() => {
    const url = attachment?.previewUrl;
    return () => {
      if (url && url.startsWith('blob:') && typeof URL !== 'undefined'
        && typeof URL.revokeObjectURL === 'function') {
        URL.revokeObjectURL(url);
      }
    };
  }, [attachment?.previewUrl]);

  // 面板关掉时把还在飞的上传断掉，别让它回来改一个已经没人看的组件。
  useEffect(() => () => {
    uploadAbortRef.current?.abort();
  }, []);

  const submit = () => {
    const text = draft.trim();
    // 上传中的附件还没有 id，这时候发出去等于助手看不见它 —— 而用户会以为看得见。
    if (!text || busy || disabled || attachment?.status === 'uploading') return;
    setDraft('');
    /*
     * 附件**刻意不在这里清空**。
     *
     * 用户很可能是「先给图 → 助手问模板叫什么名字 → 他回答」，清掉的话第二轮那张图就已经
     * 从候选里消失了，助手只能回一句「我看不到图片」。移除是用户显式点掉的动作，
     * 由上面那个 × 负责。
     */
    send(text, attachment?.status === 'ready' ? [{ mediaAssetId: attachment.assetId }] : []);
  };

  return (
    <Flex vertical className="as-panel" data-testid="assistant-panel">
      <Flex className="as-top" justify="space-between" align="center" gap={8}>
        <Flex className="as-top-main" gap={6} align="center">
          <Select
            className="as-session-select"
            size="small"
            open={conversationPickerOpen}
            onOpenChange={setConversationPickerOpen}
            value={conversations.some((conversation) => conversation.id === conversationId) ? conversationId : undefined}
            placeholder="选择会话"
            aria-label="选择助手会话"
            options={conversations.map((conversation) => ({
              value: conversation.id,
              label: conversation.status === 'ACTIVE'
                ? '当前会话'
                : `归档 ${conversation.lastActivityAt?.slice(0, 10) ?? ''}`,
            }))}
            onChange={(id) => {
              setConversationPickerOpen(false);
              void openConversation(id);
            }}
          />
          <Button
            className="as-icon-btn"
            size="small"
            type="text"
            icon={<DeleteOutlined />}
            aria-label="删除当前会话"
            disabled={!conversations.some((conversation) => conversation.id === conversationId) || busy}
            onClick={() => { void deleteConversation(conversationId); }}
          />
        </Flex>
        <Button
          className="as-ghost-btn"
          size="small"
          onClick={startNewConversation}
          // 空的对话没什么可清；请求在飞时切换会把结果写进错误的上下文。
          disabled={busy || items.length === 0}
        >
          新会话
        </Button>
      </Flex>

      <Flex className="as-stream" vertical gap={12}>
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

        {/*
          回放失败。它必须与「刚开了一段新会话」区分开 —— 两种情况的面板都是空的，
          而用户要做的事完全相反（一个什么都不用做，一个得刷新或换台设备）。
          **不写进对话**：那会让人以为「我刚才那句话失败了」，可他还什么都没说。
        */}
        {historyError && (
          <Alert
            type="warning"
            showIcon
            message="上面这段对话没能读回来"
            description={`${historyError}。记录不会因此丢失，你可以直接开始新对话，或刷新后再试一次。`}
            data-testid="assistant-history-unavailable"
          />
        )}

        {items.length === 0 && !unavailable && (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={<Text type="secondary">可以直接用一句话交代事情，比如：</Text>}
          >
            <Flex className="as-examples" vertical gap={6} align="center">
              {EXAMPLES.map((example) => (
                <button
                  type="button"
                  key={example}
                  className="as-example"
                  // 示例本来就是拿来照抄的：点一下直接填进输入框，不逼用户手打一遍。
                  onClick={() => setDraft(example)}
                >
                  {example}
                </button>
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

      <Flex className="as-composer" vertical gap={8}>
        {/*
          语境被裁剪的提示。放在这里（而不是每条消息上）是因为它是**会话级状态**：
          一旦开始丢，后面每一轮都会丢，逐条显示只会变成一片重复的噪音。
          措辞用「记不住」而不是「不在上下文里」—— 后者是术语，用户要判断的是
          「我需不需要重说一遍」，那句话得直接给出这个判断。
        */}
        {trimmedHistory !== null && (
          <Text type="secondary" className="as-note" data-testid="assistant-history-trim-note">
            较早的 {trimmedHistory} 条对话我已经记不住了；需要时请再提一次。
          </Text>
        )}
        {compactedHistory !== null && (
          <Text type="secondary" className="as-note" data-testid="assistant-history-compaction-note">
            较早的 {compactedHistory} 条对话已整理成摘要，细节可能不完整；完整原文仍可回看。
          </Text>
        )}
        {pending && (
          <Text className="as-note is-warn">
            有一条操作等着你确认，确认前不会执行。
          </Text>
        )}
        {/*
          附件条。三种状态都要看得见 —— 尤其是「传完了没有」：发送按钮在上传期间是禁用的，
          不说清楚原因的话，用户只会觉得按钮坏了。
        */}
        {attachment && (
          <Flex className="as-attachment" align="center" gap={8} data-testid="assistant-attachment">
            {attachment.previewUrl && (
              <img className="as-attachment-thumb" src={attachment.previewUrl} alt="待发送的图片" />
            )}
            <Flex className="as-attachment-body" vertical>
              <Text ellipsis className="as-attachment-name">{attachment.name}</Text>
              {attachment.status === 'uploading' && (
                <Text type="secondary" className="as-attachment-note">
                  <LoadingOutlined /> 正在上传，传完才能发出去
                </Text>
              )}
              {attachment.status === 'ready' && (
                <Text className="as-attachment-note is-ok">已就绪，发消息时一起带过去</Text>
              )}
              {attachment.status === 'failed' && (
                <Text className="as-attachment-note is-bad">{attachment.error}</Text>
              )}
            </Flex>
            <Button
              className="as-icon-btn"
              size="small"
              type="text"
              icon={<CloseOutlined />}
              aria-label="移除图片"
              onClick={removeAttachment}
            />
          </Flex>
        )}
        <Input.TextArea
          className="as-input"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder={disabled ? '助手未开启' : '说一句话，例如：把和张总确认报价标记完成'}
          autoSize={{ minRows: 1, maxRows: 4 }}
          maxLength={2000}
          disabled={disabled}
          aria-label="给助手的话"
          // 截图直接贴进来是用户最顺手的动作，不该逼他先存成文件。
          onPaste={(event) => {
            const pasted = event.clipboardData?.files?.[0];
            if (pasted) {
              event.preventDefault();
              void attach(pasted);
            }
          }}
          onPressEnter={(event) => {
            if (!event.shiftKey) {
              event.preventDefault();
              submit();
            }
          }}
        />
        <Flex className="as-toolbar" gap={8} align="center">
          <Button
            className="as-tool"
            icon={<PictureOutlined />}
            aria-label="添加图片"
            disabled={disabled}
            onClick={() => fileInputRef.current?.click()}
          >
            图片
          </Button>
          <Button
            className="as-submit"
            type="primary"
            icon={<SendOutlined />}
            aria-label="发送给助手"
            loading={busy}
            disabled={disabled || !draft.trim() || attachment?.status === 'uploading'}
            onClick={submit}
          >
            发送
          </Button>
        </Flex>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/jpeg,image/png"
          style={{ display: 'none' }}
          aria-label="选择图片"
          onChange={(event) => {
            const chosen = event.target.files?.[0];
            // 清掉 value：连选同一张图两次也要能触发 change，否则第二次点了没反应。
            event.target.value = '';
            if (chosen) void attach(chosen);
          }}
        />
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
  if (item.role === 'user') {
    return (
      <Flex className="as-row" justify="flex-end">
        <Bubble tone="user">{item.text}</Bubble>
      </Flex>
    );
  }

  if (item.streaming) {
    return (
      <Flex className="as-row" vertical gap={6} align="flex-start">
        {/*
          正文到达之前显示进度：只读那一轮可以静默几十秒，那段时间屏幕上必须有东西在动。
          图标只是装饰（`aria-hidden`）—— 「在动」这件事由文字说，读屏用户不该只听到一个加载图标。
        */}
        <Bubble tone="streaming" testId="assistant-streaming">
          {item.text || <Text type="secondary">{item.progress}</Text>}
          <LoadingOutlined aria-hidden className="as-cursor" />
        </Bubble>
      </Flex>
    );
  }

  if (item.kind === 'CONFIRMATION_REQUIRED' && item.proposal && actionable) {
    return (
      <div className="as-confirm">
        <Flex vertical gap={10}>
          <Space size={6}>
            <ExclamationCircleOutlined className="as-confirm-icon" />
            <Text strong>需要你确认</Text>
            <Tag className="as-tag is-warn">尚未执行</Tag>
          </Space>
          {/* 正文可能是长文本（一封邮件草稿）：保留换行，不许被压成一行。 */}
          <Paragraph className="as-confirm-body">{item.proposal.summary}</Paragraph>
          {item.proposal.changes && item.proposal.changes.length > 0 && (
            <ChangeList changes={item.proposal.changes} />
          )}
          <Space>
            <Button className="as-confirm-ok" type="primary" onClick={onConfirm} loading={busy}>确认</Button>
            <Button onClick={onCancel} disabled={busy}>取消</Button>
            {busy && <Text type="secondary">正在执行，请稍候…</Text>}
          </Space>
        </Flex>
      </div>
    );
  }

  if (item.kind === 'ERROR') {
    return (
      <Flex className="as-row" vertical gap={6} align="flex-start">
        <Space size={6}>
          <Tag className="as-tag is-bad">失败</Tag>
          {item.errorCode && <Text type="secondary" className="as-error-code">{item.errorCode}</Text>}
        </Space>
        <Bubble tone="error">{item.text}</Bubble>
        {isLast && item.retryable && (
          <Button size="small" className="as-retry" onClick={onRetry} disabled={busy}>重试</Button>
        )}
      </Flex>
    );
  }

  return (
    <Flex className="as-row" vertical gap={6} align="flex-start">
      {item.kind === 'EXECUTED' && <Tag className="as-tag is-ok">已执行</Tag>}
      {item.kind === 'QUESTION' && <Tag className="as-tag is-q">需要补充信息</Tag>}
      {/*
        只标注**来自历史回放**的那一条：回放不带 `proposal`（见 itemOfHistory）。
        本会话内刚处理完的那一条同样有 kind 却已无卡片，但它的结果就显示在下面，
        再打一个标签会和结果自相矛盾。
      */}
      {item.kind === 'CONFIRMATION_REQUIRED' && !item.proposal && <Tag className="as-tag">当时待确认</Tag>}
      <Bubble tone="assistant">{item.text}</Bubble>
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

/**
 * 气泡的四种语气。
 *
 * 用 `tone` 而不是「传一个背景色进来」：四种语气的取色（以及圆角的那个小尖角、
 * 错误气泡的描边）是一整套决定，散在调用点上早晚会长出第五种配色。
 */
function Bubble({
  children,
  tone,
  testId,
}: {
  children: React.ReactNode;
  tone: 'user' | 'assistant' | 'streaming' | 'error';
  /** 只为了把「还在推」这一种气泡定位出来（不传时不渲染属性）。 */
  testId?: string;
}) {
  return (
    <div data-testid={testId} className={`as-bubble is-${tone}`}>
      {children}
    </div>
  );
}
