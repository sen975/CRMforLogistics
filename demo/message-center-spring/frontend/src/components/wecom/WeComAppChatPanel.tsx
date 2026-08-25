import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App, Button, Drawer, Form, Input, Modal, Select, Space, Spin } from 'antd';
import { EditOutlined, MessageOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import {
  createWeComAppChat,
  fetchWeComAppChat,
  sendWeComAppChatMessage,
  updateWeComAppChat,
} from '../../api/endpoints';
import WeComProviderView from './WeComProviderView';

const ids = (value?: string) => value?.split(',').map((item) => item.trim()).filter(Boolean) ?? [];

export default function WeComAppChatPanel({ authCorpId }: { authCorpId: string }) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [chatId, setChatId] = useState('');
  const [activeChatId, setActiveChatId] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [updateOpen, setUpdateOpen] = useState(false);
  const [sendOpen, setSendOpen] = useState(false);
  const [createForm] = Form.useForm();
  const [updateForm] = Form.useForm();
  const [sendForm] = Form.useForm();

  const chat = useQuery({
    queryKey: ['wecom', 'app-chat', authCorpId, activeChatId],
    queryFn: () => fetchWeComAppChat(authCorpId, activeChatId),
    enabled: !!authCorpId && !!activeChatId,
    retry: false,
  });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['wecom', 'app-chat', authCorpId, activeChatId] });

  const createMutation = useMutation({
    mutationFn: (value: { chatId?: string; name: string; owner: string; users: string }) =>
      createWeComAppChat(authCorpId, { ...value, userList: ids(value.users) }),
    onSuccess: (data, values) => {
      const createdId = String(data.chatid ?? values.chatId ?? '');
      if (createdId) { setChatId(createdId); setActiveChatId(createdId); }
      setCreateOpen(false); createForm.resetFields(); message.success('群聊已创建');
    },
  });
  const updateMutation = useMutation({
    mutationFn: (value: { name?: string; owner?: string; addUsers?: string; removeUsers?: string }) =>
      updateWeComAppChat(authCorpId, activeChatId, {
        name: value.name || undefined, owner: value.owner || undefined,
        addUsers: ids(value.addUsers), removeUsers: ids(value.removeUsers),
      }),
    onSuccess: () => { setUpdateOpen(false); updateForm.resetFields(); refresh(); message.success('群聊已更新'); },
  });
  const sendMutation = useMutation({
    mutationFn: (value: { messageType: string; content: string; safe?: boolean }) =>
      sendWeComAppChatMessage(authCorpId, activeChatId, {
        messageType: value.messageType,
        content: value.messageType === 'text' ? { content: value.content } : JSON.parse(value.content),
        safe: value.safe,
      }),
    onSuccess: () => { setSendOpen(false); sendForm.resetFields(); message.success('消息已发送'); },
    onError: () => message.error('发送失败，请检查消息内容'),
  });

  return (
    <section className="wecom-panel" aria-label="应用群聊管理">
      <div className="wecom-toolbar">
        <Input aria-label="应用群聊 Chat ID" value={chatId} onChange={(event) => setChatId(event.target.value)}
          placeholder="输入 Chat ID" onPressEnter={() => setActiveChatId(chatId.trim())} />
        <Button aria-label="查询群聊" icon={<SearchOutlined />} disabled={!chatId.trim()} onClick={() => setActiveChatId(chatId.trim())}>查询群聊</Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>创建群聊</Button>
      </div>
      <Spin spinning={chat.isFetching}>
        <WeComProviderView data={chat.data} emptyText={activeChatId ? '未查询到群聊' : '输入 Chat ID 后查询'} />
      </Spin>
      {chat.data && (
        <Space className="wecom-panel-actions">
          <Button icon={<EditOutlined />} onClick={() => setUpdateOpen(true)}>修改群聊</Button>
          <Button icon={<MessageOutlined />} onClick={() => setSendOpen(true)}>发送消息</Button>
        </Space>
      )}
      <Modal title="创建应用群聊" open={createOpen} onCancel={() => setCreateOpen(false)}
        onOk={() => createForm.submit()} confirmLoading={createMutation.isPending}>
        <Form form={createForm} layout="vertical" onFinish={(value) => createMutation.mutate(value)}>
          <Form.Item name="chatId" label="Chat ID"><Input maxLength={128} /></Form.Item>
          <Form.Item name="name" label="群名称" rules={[{ required: true }]}><Input maxLength={50} /></Form.Item>
          <Form.Item name="owner" label="群主成员 ID" rules={[{ required: true }]}><Input maxLength={128} /></Form.Item>
          <Form.Item name="users" label="成员 ID" extra="使用英文逗号分隔，必须包含群主" rules={[{ required: true }]}><Input.TextArea rows={3} /></Form.Item>
        </Form>
      </Modal>
      <Drawer title="修改群聊" open={updateOpen} onClose={() => setUpdateOpen(false)} width="min(560px, 100vw)"
        extra={<Button type="primary" loading={updateMutation.isPending} onClick={() => updateForm.submit()}>保存修改</Button>}>
        <Form form={updateForm} layout="vertical" onFinish={(value) => updateMutation.mutate(value)}>
          <Form.Item name="name" label="群名称"><Input maxLength={50} /></Form.Item>
          <Form.Item name="owner" label="新群主成员 ID"><Input maxLength={128} /></Form.Item>
          <Form.Item name="addUsers" label="新增成员 ID"><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="removeUsers" label="移除成员 ID"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Drawer>
      <Modal title="发送群聊消息" open={sendOpen} onCancel={() => setSendOpen(false)}
        onOk={() => sendForm.submit()} confirmLoading={sendMutation.isPending}>
        <Form form={sendForm} layout="vertical" initialValues={{ messageType: 'text' }} onFinish={(value) => sendMutation.mutate(value)}>
          <Form.Item name="messageType" label="消息类型" rules={[{ required: true }]}>
            <Select options={['text', 'markdown', 'image', 'voice', 'video', 'file', 'news', 'mpnews', 'textcard'].map((value) => ({ value }))} />
          </Form.Item>
          <Form.Item name="content" label="消息内容" rules={[{ required: true }]}><Input.TextArea rows={5} /></Form.Item>
        </Form>
      </Modal>
    </section>
  );
}
