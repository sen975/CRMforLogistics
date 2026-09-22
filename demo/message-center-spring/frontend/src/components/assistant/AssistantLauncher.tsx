import { useState } from 'react';
import { Drawer, FloatButton } from 'antd';
import { RobotOutlined } from '@ant-design/icons';
import { AssistantPanel } from './AssistantPanel';

/**
 * 全局助手入口：右下角的悬浮按钮 + 会话面板。
 *
 * <h2>为什么挂在 `AppLayout` 而不是每个页面</h2>
 * 「在哪儿都能唤起」是这个功能的价值之一：用户想起一件事时，他很可能正待在联系人列表而不是待办页。
 * 挂在布局上，路由切换不会把它卸掉，对话上下文也就跟着用户走。
 *
 * <h2>面板的挂载与关闭</h2>
 * 面板**不随关闭卸载**：抽屉关掉只是收起来，对话还在。否则用户手滑点一下外面，
 * 刚说了一半的话和那张还没确认的卡片就一起没了 —— 而那张卡片背后是一次真实的授权。
 */
export function AssistantLauncher() {
  const [open, setOpen] = useState(false);

  return (
    <>
      <FloatButton
        type="primary"
        icon={<RobotOutlined />}
        aria-label="AI 助手"
        tooltip="AI 助手"
        onClick={() => setOpen(true)}
      />
      <Drawer
        title="AI 助手"
        placement="right"
        width="min(420px, 100vw)"
        open={open}
        onClose={() => setOpen(false)}
        styles={{ body: { padding: 0 } }}
      >
        <AssistantPanel />
      </Drawer>
    </>
  );
}
