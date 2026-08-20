import React from 'react';
import { act, cleanup, render } from '@testing-library/react';
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
    await act(async () => { await Promise.resolve(); });
    expect(FakeWebSocket.instances).toHaveLength(1);

    FakeWebSocket.instances[0].onopen();
    expect(JSON.parse(FakeWebSocket.instances[0].sent[0]).token).toBe('access-before-rotation');

    FakeWebSocket.instances[0].onclose();
    await act(async () => {
      vi.advanceTimersByTime(5000);
      await Promise.resolve();
    });
    expect(FakeWebSocket.instances).toHaveLength(2);
    FakeWebSocket.instances[1].onopen();

    expect(JSON.parse(FakeWebSocket.instances[1].sent[0]).token).toBe('access-after-rotation');
    expect(FakeWebSocket.instances[1].url).not.toContain('access-after-rotation');
    expect(getFreshAccessTokenMock).toHaveBeenCalledTimes(2);
  });
});
