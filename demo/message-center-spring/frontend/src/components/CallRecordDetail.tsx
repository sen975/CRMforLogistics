import { useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { Typography, Tag, Button, Input, Space, List, App } from 'antd';
import { ReloadOutlined, EditOutlined, SaveOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchCallRecord, createAudioSession, retryCallRecord, reviseTranscript, reviseNote } from '../api/endpoints';
import type { ApiError } from '../api/types';
import { decideTranscriptSave } from '../utils/callRecordRevision';
import { canRetryTranscription, splitTranscriptDisplayLines } from '../utils/callRecordTranscript';

const { Text, Paragraph, Title } = Typography;
const { TextArea } = Input;

interface CallRecordDetailProps {
  callRecordId: string;
}

const apiError = (error: unknown): ApiError | undefined =>
  axios.isAxiosError<ApiError>(error) ? error.response?.data : undefined;

export default function CallRecordDetail({ callRecordId }: CallRecordDetailProps) {
  const [editingTranscript, setEditingTranscript] = useState(false);
  const [transcriptDraft, setTranscriptDraft] = useState('');
  const [transcriptBaseRevisionId, setTranscriptBaseRevisionId] = useState<string | null>(null);
  const [savingTranscript, setSavingTranscript] = useState(false);
  const [editingNote, setEditingNote] = useState(false);
  const [noteDraft, setNoteDraft] = useState('');
  const [audioSessionCallRecordId, setAudioSessionCallRecordId] = useState<string | null>(null);
  const [audioSessionError, setAudioSessionError] = useState(false);
  const [audioSessionRetry, setAudioSessionRetry] = useState(0);
  const audioRef = useRef<HTMLAudioElement>(null);
  const sessionIntervalRef = useRef<ReturnType<typeof setInterval>>();
  const { message } = App.useApp();
  const queryClient = useQueryClient();

  const { data: record, isLoading } = useQuery({
    queryKey: ['callRecord', callRecordId],
    queryFn: () => fetchCallRecord(callRecordId),
    enabled: !!callRecordId,
    refetchInterval: (query) => {
      const state = query.state.data?.transcription?.state;
      return state === 'queued' || state === 'processing' ? 5000 : false;
    },
  });

  useEffect(() => {
    let active = true;
    setAudioSessionCallRecordId(null);
    setAudioSessionError(false);
    if (!record) return () => { active = false; };

    const refreshAudioSession = () => {
      createAudioSession(callRecordId)
        .then(() => {
          if (active) {
            setAudioSessionCallRecordId(callRecordId);
            setAudioSessionError(false);
          }
        })
        .catch(() => {
          if (active) {
            setAudioSessionCallRecordId(null);
            setAudioSessionError(true);
          }
        });
    };

    refreshAudioSession();
    sessionIntervalRef.current = setInterval(refreshAudioSession, 240_000);
    return () => {
      active = false;
      if (sessionIntervalRef.current) clearInterval(sessionIntervalRef.current);
    };
  }, [audioSessionRetry, callRecordId, record?.id]);

  if (isLoading) return <div style={{ padding: 16 }}>加载中...</div>;
  if (!record) return <div style={{ padding: 16 }}>未找到录音记录</div>;

  const directionTag = record.direction === 'inbound'
    ? <Tag color="blue">呼入</Tag>
    : <Tag color="green">呼出</Tag>;

  const stateMap: Record<string, { color: string; label: string }> = {
    queued: { color: 'default', label: '排队中' },
    processing: { color: 'processing', label: '转录中' },
    completed: { color: 'success', label: '已完成' },
    failed: { color: 'error', label: '失败' },
  };
  const s = stateMap[record.transcription.state] ?? { color: 'default', label: record.transcription.state };

  const formatSeconds = (sec: number) => {
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  const formatBytes = (b: number) => {
    if (b < 1024) return `${b} B`;
    if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
    return `${(b / (1024 * 1024)).toFixed(1)} MB`;
  };

  const handleRetry = async () => {
    try {
      await retryCallRecord(callRecordId, record.clientRequestId);
      message.success('已重新加入转录队列');
      queryClient.invalidateQueries({ queryKey: ['callRecord', callRecordId] });
    } catch {
      message.error('重试失败');
    }
  };

  const handleSaveTranscript = async () => {
    if (savingTranscript) return;
    setSavingTranscript(true);
    try {
      const latest = await fetchCallRecord(callRecordId);
      queryClient.setQueryData(['callRecord', callRecordId], latest);
      const decision = decideTranscriptSave(transcriptBaseRevisionId, latest);
      if (decision.kind === 'conflict') {
        message.error('转录稿已被更新，请核对最新内容后重新编辑');
        return;
      }
      const updated = await reviseTranscript(callRecordId, {
        text: transcriptDraft,
        expectedVersion: decision.expectedVersion,
      });
      queryClient.setQueryData(['callRecord', callRecordId], updated);
      setEditingTranscript(false);
      setTranscriptBaseRevisionId(updated.currentRevisionId);
      message.success('转录稿已保存');
    } catch (error) {
      const failure = apiError(error);
      if (failure?.code === 'TRANSCRIPT_VERSION_CONFLICT') {
        await queryClient.invalidateQueries({ queryKey: ['callRecord', callRecordId] });
        message.error('转录稿已被更新，请核对最新内容后重新编辑');
      } else {
        message.error(failure?.message || '转录稿保存失败');
      }
    } finally {
      setSavingTranscript(false);
    }
  };

  const handleSaveNote = async () => {
    try {
      const updated = await reviseNote(callRecordId, {
        note: noteDraft,
        expectedVersion: record.version,
      });
      queryClient.setQueryData(['callRecord', callRecordId], updated);
      setEditingNote(false);
      message.success('备注已保存');
    } catch {
      message.error('保存失败，版本可能已更新');
    }
  };

  return (
    <div style={{ padding: 16 }}>
      <Title level={5} style={{ marginBottom: 12 }}>
        通话记录
      </Title>

      <Space direction="vertical" style={{ width: '100%' }} size="small">
        <div>
          {directionTag}
          <Tag color={s.color}>{s.label}</Tag>
          {record.transcription.error && (
            <Tag color="red" title={record.transcription.error.message}>
              {record.transcription.error.code}
              {record.transcription.error.message ? ': ' + record.transcription.error.message : ''}
            </Tag>
          )}
          {record.transcription.nextAttemptAt && record.transcription.state === 'queued' && (
            <Tag color="orange">
              下次重试: {new Date(record.transcription.nextAttemptAt).toLocaleTimeString('zh-CN')}
            </Tag>
          )}
        </div>

        <Text type="secondary">
          号码: {record.phonePointId}
        </Text>
        <Text type="secondary">
          时间: {new Date(record.occurredAt).toLocaleString('zh-CN')}
        </Text>
        <Text type="secondary">
          时长: {formatSeconds(record.audio.durationSeconds)} | 大小: {formatBytes(record.audio.sizeBytes)}
        </Text>
        <Text type="secondary">
          文件: {record.audio.originalFileName}
        </Text>

        {audioSessionCallRecordId === callRecordId ? (
          <audio
            ref={audioRef}
            controls
            style={{ width: '100%', marginTop: 8 }}
            src={`/api/v1/call-records/${callRecordId}/audio`}
          />
        ) : (
          <Button
            icon={<ReloadOutlined />}
            loading={!audioSessionError}
            onClick={() => setAudioSessionRetry((attempt) => attempt + 1)}
            danger={audioSessionError}
            type={audioSessionError ? 'default' : 'text'}
          >
            {audioSessionError ? '音频加载失败，重试' : '音频准备中'}
          </Button>
        )}

        {canRetryTranscription(record) && (
          <Button
            icon={<ReloadOutlined />}
            onClick={handleRetry}
            type="primary"
            size="small"
            danger
          >
            重新转录
          </Button>
        )}

        {record.transcription.result && (
          <>
            <Title level={5} style={{ marginTop: 16, marginBottom: 8 }}>
              转录原文
            </Title>
            <Paragraph
              style={{
                background: '#f5f5f5',
                padding: 8,
                borderRadius: 4,
                maxHeight: 200,
                overflow: 'auto',
                fontSize: 13,
              }}
            >
              {record.transcription.result.originalText}
            </Paragraph>
          </>
        )}

        {record.transcription.result?.segments && record.transcription.result.segments.length > 0 && (
          <>
            <Title level={5} style={{ marginTop: 16, marginBottom: 8 }}>
              时间轴分段
            </Title>
            <List
              size="small"
              dataSource={record.transcription.result.segments}
              renderItem={(seg) => (
                <List.Item style={{ padding: '4px 0' }}>
                  <Space>
                    <Tag style={{ fontSize: 11 }}>
                      {formatSeconds(seg.startSeconds)}-{formatSeconds(seg.endSeconds)}
                    </Tag>
                    <Text style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>
                      {splitTranscriptDisplayLines(seg.text).map((line, index, lines) => (
                        <span key={`${seg.startSeconds}-${index}`}>
                          {line}
                          {index < lines.length - 1 && <br />}
                        </span>
                      ))}
                    </Text>
                  </Space>
                </List.Item>
              )}
              style={{ maxHeight: 300, overflow: 'auto' }}
            />
          </>
        )}

        {record.transcription.state === 'completed' && (
          <>
            <Title level={5} style={{ marginTop: 16, marginBottom: 8 }}>
              人工修订稿
              {!editingTranscript && (
                <Button
                  type="link"
                  size="small"
                  icon={<EditOutlined />}
                  onClick={() => {
                    setTranscriptDraft(record.revisions.length > 0
                      ? record.revisions[record.revisions.length - 1].text
                      : record.transcription.result?.originalText ?? '');
                    setTranscriptBaseRevisionId(record.currentRevisionId);
                    setEditingTranscript(true);
                  }}
                >
                  编辑
                </Button>
              )}
            </Title>
            {editingTranscript ? (
              <Space direction="vertical" style={{ width: '100%' }}>
                <TextArea
                  value={transcriptDraft}
                  onChange={(e) => setTranscriptDraft(e.target.value)}
                  rows={6}
                />
                <Space>
                  <Button
                    type="primary"
                    size="small"
                    icon={<SaveOutlined />}
                    onClick={handleSaveTranscript}
                    loading={savingTranscript}
                    disabled={savingTranscript}
                  >
                    保存
                  </Button>
                  <Button size="small" onClick={() => setEditingTranscript(false)}>
                    取消
                  </Button>
                </Space>
              </Space>
            ) : (
              <Paragraph
                style={{
                  background: '#fafafa',
                  padding: 8,
                  borderRadius: 4,
                  maxHeight: 200,
                  overflow: 'auto',
                  fontSize: 13,
                }}
              >
                {record.revisions.length > 0
                  ? record.revisions[record.revisions.length - 1].text
                  : (record.transcription.result?.originalText ?? '暂无')}
              </Paragraph>
            )}

            {record.revisions.length > 0 && (
              <>
                <Title level={5} style={{ marginTop: 12, marginBottom: 8 }}>
                  修订历史 ({record.revisions.length})
                </Title>
                <List
                  size="small"
                  dataSource={[...record.revisions].reverse()}
                  renderItem={(rev) => (
                    <List.Item style={{ padding: '4px 0', fontSize: 12 }}>
                      <Space direction="vertical" size={0}>
                        <Text type="secondary" style={{ fontSize: 11 }}>
                          {new Date(rev.editedAt).toLocaleString('zh-CN')} by {rev.editedBy}
                        </Text>
                        <Text style={{ fontSize: 12 }}>{rev.text}</Text>
                      </Space>
                    </List.Item>
                  )}
                  style={{ maxHeight: 200, overflow: 'auto' }}
                />
              </>
            )}
          </>
        )}

        <Title level={5} style={{ marginTop: 16, marginBottom: 8 }}>
          备注
          {!editingNote && (
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setNoteDraft(record.note ?? '');
                setEditingNote(true);
              }}
            >
              编辑
            </Button>
          )}
        </Title>
        {editingNote ? (
          <Space direction="vertical" style={{ width: '100%' }}>
            <TextArea
              value={noteDraft}
              onChange={(e) => setNoteDraft(e.target.value)}
              rows={3}
            />
            <Space>
              <Button
                type="primary"
                size="small"
                icon={<SaveOutlined />}
                onClick={handleSaveNote}
              >
                保存
              </Button>
              <Button size="small" onClick={() => setEditingNote(false)}>
                取消
              </Button>
            </Space>
          </Space>
        ) : (
          <Text type="secondary" style={{ fontSize: 13 }}>
            {record.note || '暂无备注'}
          </Text>
        )}
      </Space>
    </div>
  );
}
