import { Button, Flex } from 'antd';

export interface WeComConversationOption {
  id: string;
  type: 'DIRECT' | 'GROUP';
  displayName: string;
}

export function WeComConversationSelector({
  options,
  selectedId,
  onSelect,
}: {
  options: WeComConversationOption[];
  selectedId: string;
  onSelect: (id: string) => void;
}) {
  if (options.length <= 1) return null;
  return (
    <Flex gap={4} wrap="wrap" style={{ padding: '6px 12px', borderBottom: '1px solid #f0f0f0' }}>
      {options.map((option) => (
        <Button
          key={option.id}
          type={option.id === selectedId ? 'primary' : 'default'}
          size="small"
          onClick={() => onSelect(option.id)}
          aria-pressed={option.id === selectedId}
        >
          {option.displayName || (option.type === 'GROUP' ? '企业微信群' : '企业微信会话')}
        </Button>
      ))}
    </Flex>
  );
}
