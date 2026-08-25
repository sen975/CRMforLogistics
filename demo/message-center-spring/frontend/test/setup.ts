import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

class ResizeObserverStub implements ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();

  get length() {
    return this.values.size;
  }

  clear() {
    this.values.clear();
  }

  getItem(key: string) {
    return this.values.get(key) ?? null;
  }

  key(index: number) {
    return [...this.values.keys()][index] ?? null;
  }

  removeItem(key: string) {
    this.values.delete(key);
  }

  setItem(key: string, value: string) {
    this.values.set(key, String(value));
  }
}

Object.defineProperty(window, 'localStorage', {
  configurable: true,
  value: new MemoryStorage(),
});

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
});

Object.defineProperty(window, 'ResizeObserver', {
  writable: true,
  value: ResizeObserverStub,
});

Object.defineProperty(window, 'scrollTo', {
  writable: true,
  value: () => undefined,
});

const getComputedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = (element: Element) => getComputedStyle(element);

afterEach(() => {
  cleanup();
});
