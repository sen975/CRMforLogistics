// No-op ActionCable connector — stub for standalone frontend.
// Replace with real WebSocket connector when you build your backend
// (e.g. connect to Python/Django Channels, Socket.IO, or raw WS).
class BaseActionCableConnector {
  static isDisconnected = false;

  constructor(app) {
    this.app = app;
    this.events = {};
    this.subscription = { updatePresence() {} };
  }

  isAValidEvent() { return true; }
  onReconnect() {}
  onDisconnected() {}
  disconnect() {}

  onReceived({ event, data } = {}) {
    if (this.isAValidEvent(data)) {
      if (this.events[event] && typeof this.events[event] === 'function') {
        this.events[event](data);
      }
    }
  }
}

export default BaseActionCableConnector;
