import '@testing-library/jest-dom/vitest';
import { expect, it, vi } from 'vitest';
import { publishAssistantEvent, subscribeAssistantEvents } from './assistantEvents';

const event = { type: 'assistant-action-executed' } as const;

it('delivers the event to every subscriber and stops after unsubscribe', () => {
  const first = vi.fn();
  const second = vi.fn();
  const stopFirst = subscribeAssistantEvents(first);
  const stopSecond = subscribeAssistantEvents(second);

  try {
    publishAssistantEvent(event);
    expect(first).toHaveBeenCalledTimes(1);
    expect(second).toHaveBeenCalledTimes(1);

    stopFirst();
    publishAssistantEvent(event);
    expect(first).toHaveBeenCalledTimes(1);
    expect(second).toHaveBeenCalledTimes(2);
  } finally {
    stopFirst();
    stopSecond();
  }
});

/**
 * 一次**已经执行成功**的动作，不能因为某个订阅方渲染时抛错，就在调用方那里变成失败。
 * 这不只是「健壮性」：调用方眼里的失败意味着「没做成」，而事实上待办已经改掉了 ——
 * 那正是「不得把成功静默成失败」要防的那件事。
 */
it('keeps a broken subscriber from taking down the others', () => {
  const broken = vi.fn(() => {
    throw new Error('subscriber exploded');
  });
  const healthy = vi.fn();
  const stopBroken = subscribeAssistantEvents(broken);
  const stopHealthy = subscribeAssistantEvents(healthy);

  try {
    expect(() => publishAssistantEvent(event)).not.toThrow();
    expect(broken).toHaveBeenCalledTimes(1);
    expect(healthy).toHaveBeenCalledTimes(1);
  } finally {
    stopBroken();
    stopHealthy();
  }
});

it('lets a subscriber unsubscribe from inside its own callback', () => {
  const other = vi.fn();
  const stopSelf = subscribeAssistantEvents(() => stopSelf());
  const stopOther = subscribeAssistantEvents(other);

  try {
    // 遍历的是订阅列表的副本：回调里退订不该让这一次的其余订阅者被跳过。
    publishAssistantEvent(event);
    expect(other).toHaveBeenCalledTimes(1);
  } finally {
    stopSelf();
    stopOther();
  }
});
