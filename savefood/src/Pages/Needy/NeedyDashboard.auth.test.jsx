import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import NeedyDashboard from './NeedyDashboard';
const { authFetchMock, getFreshAccessTokenMock } = vi.hoisted(() => ({
  authFetchMock: vi.fn(),
  getFreshAccessTokenMock: vi.fn(),
}));
vi.mock('../../api', () => ({
  API_URL: '',
  authFetch: authFetchMock,
  getFreshAccessToken: getFreshAccessTokenMock,
}));
vi.mock('../../context/AuthContext', () => ({
  useAuth: () => ({
    user: { token: 'render-time-stale-token', role: 'needy', relatedId: 4 },
    logout: vi.fn(),
  }),
}));
vi.mock('react-router-dom', () => ({ useNavigate: () => vi.fn() }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: key => key }) }));
vi.mock('react-qr-code', () => ({ default: () => null }));
vi.mock('@pbe/react-yandex-maps', () => ({
  YMaps: ({ children }) => <>{children}</>,
  Map: ({ children }) => <>{children}</>,
  Placemark: () => null,
}));
vi.mock('../Auth/AddressInput', () => ({ default: () => null }));
vi.mock('../../components/AccountLinks', () => ({ default: () => null }));
vi.mock('../../components/PushToggle', () => ({ default: () => null }));
vi.mock('../../components/OnboardingChecklist', () => ({ default: () => null }));
vi.mock('../../components/TicketChat', () => ({ default: () => null }));
vi.mock('../../components/MonoIcon', () => ({ default: () => null }));
class FakeWebSocket {
  static instances = [];
  constructor(url) {
    this.url = url;
    this.sent = [];
    FakeWebSocket.instances.push(this);
  }
  send(message) {
    this.sent.push(message);
  }
  close() {}
}
describe('recipient WebSocket authentication', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeWebSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeWebSocket);
    authFetchMock.mockReset().mockResolvedValue({
      ok: true,
      status: 200,
      json: vi.fn().mockResolvedValue([]),
    });
    getFreshAccessTokenMock.mockReset()
      .mockResolvedValueOnce('access-before-rotation')
      .mockResolvedValueOnce('access-after-rotation');
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });
  it('reads the current access token again for a reconnect', async () => {
    render(<NeedyDashboard />);
    await flushPromises();
    expect(FakeWebSocket.instances).toHaveLength(1);
    FakeWebSocket.instances[0].onopen();
    const firstHandshake = JSON.parse(FakeWebSocket.instances[0].sent[0]);
    expect(firstHandshake.token).toBe('access-before-rotation');
    expect(firstHandshake.since_id).toBe(0);
    act(() => {
      FakeWebSocket.instances[0].onmessage({
        data: JSON.stringify({ id: 15, type: 'delivery', payload: 'live' }),
      });
    });
    FakeWebSocket.instances[0].onclose();
    await act(async () => {
      vi.advanceTimersByTime(5000);
      await Promise.resolve();
    });
    expect(FakeWebSocket.instances).toHaveLength(2);
    FakeWebSocket.instances[1].onopen();
    const reconnectHandshake = JSON.parse(FakeWebSocket.instances[1].sent[0]);
    expect(reconnectHandshake.token).toBe('access-after-rotation');
    expect(reconnectHandshake.since_id).toBe(15);
    expect(FakeWebSocket.instances[1].url).not.toContain('access-after-rotation');
    expect(getFreshAccessTokenMock).toHaveBeenCalledTimes(2);
  });
  it('waits for the REST cursor before the first WebSocket handshake', async () => {
    let completeNotifications;
    const notificationsResponse = new Promise(resolve => { completeNotifications = resolve; });
    authFetchMock.mockImplementation(url => {
      if (url === '/needy/4/notifications') return notificationsResponse;
      return Promise.resolve(response([]));
    });
    render(<NeedyDashboard />);
    await flushPromises();
    expect(FakeWebSocket.instances).toHaveLength(0);
    completeNotifications(response([
      { id: 21, type: 'before_rest_completed', payload: 'observed by REST', read: 0, created_at: '2026-08-28T00:00:00Z' },
    ]));
    await flushPromises();
    expect(FakeWebSocket.instances).toHaveLength(1);
    FakeWebSocket.instances[0].onopen();
    const handshake = JSON.parse(FakeWebSocket.instances[0].sent[0]);
    expect(handshake.since_id).toBe(21);
    expect(handshake.token).toBe('access-before-rotation');
  });
  it('deduplicates a notification observed by REST and WebSocket catch-up', async () => {
    authFetchMock.mockImplementation(url => Promise.resolve(response(
      url === '/needy/4/notifications'
        ? [{ id: 21, type: 'overlap', payload: 'same notification', read: 0, created_at: '2026-08-28T00:00:00Z' }]
        : [],
    )));
    render(<NeedyDashboard />);
    await flushPromises();
    expect(FakeWebSocket.instances).toHaveLength(1);
    act(() => {
      FakeWebSocket.instances[0].onmessage({
        data: JSON.stringify({ id: 21, type: 'overlap', payload: 'same notification' }),
      });
    });
    fireEvent.click(screen.getByRole('button', { name: /common\.notifications/ }));
    expect(screen.getAllByText('same notification')).toHaveLength(1);
  });
});
function response(data) {
  return { ok: true, status: 200, json: vi.fn().mockResolvedValue(data) };
}
async function flushPromises() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
}
