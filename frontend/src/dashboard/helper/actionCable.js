// No-op ActionCable init — stub for standalone frontend.
// Re-enable when you wire up your own backend's WebSocket.
export default {
  init(pubsubToken) {
    // eslint-disable-next-line no-console
    console.log('[CRM] ActionCable disabled — WebSocket not connected');
    return null;
  },
};
