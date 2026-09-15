import { DownOutlined } from '@ant-design/icons';
import { Button, Dropdown, type MenuProps } from 'antd';
import type { SearchMode } from '../api/types';

const labels: Record<SearchMode, string> = { contact: '联系人', tag: '标签' };

interface Props {
  value: SearchMode;
  onChange: (mode: SearchMode) => void;
}

export default function SearchModeSwitch({ value, onChange }: Props) {
  const items: MenuProps['items'] = [
    { key: 'contact', label: '联系人' },
    { key: 'tag', label: '标签' },
  ];
  return (
    <Dropdown
      trigger={['click']}
      menu={{
        items,
        selectable: true,
        selectedKeys: [value],
        onClick: ({ key }) => onChange(key as SearchMode),
      }}
    >
      <Button aria-label="搜索模式" size="middle">
        {labels[value]}
        <DownOutlined style={{ fontSize: 10 }} />
      </Button>
    </Dropdown>
  );
}
