import React from 'react';
import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  PUSH_ENDPOINT_STORAGE_KEY,
  rememberBrowserPushOwnership,
  revokeAndClearSession,
  storeSession,
} from '../api';
import PushToggle from './PushToggle';

const auth = vi.hoisted(() => ({ user: null }));
vi.mock('../context/AuthContext', () => ({ useAuth: () => auth }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: key => key }) }));

const jwt = subject => [
  window.btoa(JSON.stringify({ alg: 'none' })).replace(/=+$/, ''),
  window.btoa(JSON.stringify({ sub: subject, exp: Math.floor(Date.now() / 1000) + 900 }))
    .replace(/=+$/, ''),
  'signature',
].join('.');
const response = (status, data = {}) => ({
  ok: status >= 200 && status < 300,
  status,
  json: vi.fn().mockResolvedValue(data),
});

describe('PushToggle account ownership', () => {
  beforeEach(() => {
    window.localStorage.clear();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.stubGlobal('PushManager', function PushManager() {});
    vi.stubGlobal('Notification', {
      permission: 'granted',
      requestPermission: vi.fn().mockResolvedValue('granted'),
    });
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('removes an existing subscription owned by account A instead of inheriting it as B', async () => {
    const accountA = jwt('41');
    const accountB = jwt('52');
    auth.user = { token: accountB, role: 'needy', relatedId: 5 };
    storeSession(accountB, 'refresh-b', 'needy', 5);
    rememberBrowserPushOwnership('https://push.example/account-a', accountA);
    const unsubscribe = vi.fn().mockResolvedValue(true);
    const postMessage = vi.fn();
    const registration = {
      active: { postMessage },
      pushManager: {
        getSubscription: vi.fn().mockResolvedValue({
          endpoint: 'https://push.example/account-a', unsubscribe,
        }),
      },
    };
    vi.stubGlobal('navigator', {
      serviceWorker: {
        controller: { postMessage },
        getRegistration: vi.fn().mockResolvedValue(registration),
        register: vi.fn().mockResolvedValue(registration),
      },
    });
    const fetchMock = vi.fn().mockResolvedValue(response(200, { key: 'AQ' }));
    vi.stubGlobal('fetch', fetchMock);
    render(<PushToggle />);
    await waitFor(() => expect(unsubscribe).toHaveBeenCalledTimes(1));
    expect(window.localStorage.getItem(PUSH_ENDPOINT_STORAGE_KEY)).toBeNull();
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/push/subscribe'))).toBe(false);
    expect(postMessage).toHaveBeenCalledWith(expect.objectContaining({
      type: 'SET_PUSH_ENABLED', enabled: false,
    }));
  });

  it('preserves normal enable, disable, and re-enable behavior', async () => {
    const access = jwt('52');
    auth.user = { token: access, role: 'needy', relatedId: 5 };
    storeSession(access, 'refresh-52', 'needy', 5);
    let current = null;
    let sequence = 0;
    const makeSubscription = () => {
      const endpoint = `https://push.example/account-b-${++sequence}`;
      return {
        endpoint,
        toJSON: () => ({ endpoint, keys: { p256dh: 'key', auth: 'auth' } }),
        unsubscribe: vi.fn().mockImplementation(async () => {
          current = null;
          return true;
        }),
      };
    };
    const postMessage = vi.fn();
    const registration = {
      active: { postMessage },
      pushManager: {
        getSubscription: vi.fn().mockImplementation(async () => current),
        subscribe: vi.fn().mockImplementation(async () => {
          current = makeSubscription();
          return current;
        }),
      },
    };
    vi.stubGlobal('navigator', {
      serviceWorker: {
        controller: { postMessage },
        getRegistration: vi.fn().mockResolvedValue(registration),
        register: vi.fn().mockResolvedValue(registration),
      },
    });
    const fetchMock = vi.fn((url) => Promise.resolve(
      String(url).endsWith('/push/public_key')
        ? response(200, { key: 'AQ' }) : response(200, { ok: true }),
    ));
    vi.stubGlobal('fetch', fetchMock);
    render(<PushToggle />);
    const enableButton = await screen.findByRole('button', { name: 'push.enable' });
    await waitFor(() => expect(enableButton).not.toBeDisabled());
    fireEvent.click(enableButton);
    await screen.findByRole('button', { name: 'push.disable' });
    expect(registration.pushManager.subscribe).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: 'push.disable' }));
    await screen.findByRole('button', { name: 'push.enable' });
    fireEvent.click(screen.getByRole('button', { name: 'push.enable' }));
    await waitFor(() => expect(registration.pushManager.subscribe).toHaveBeenCalledTimes(2));
    await screen.findByRole('button', { name: 'push.disable' });
  });

  it('compensates when account A logout overtakes an in-flight enable', async () => {
    const accountA = jwt('41');
    auth.user = { token: accountA, role: 'needy', relatedId: 4 };
    storeSession(accountA, 'refresh-a', 'needy', 4);
    let current = null;
    const unsubscribe = vi.fn().mockImplementation(async () => {
      current = null;
      return true;
    });
    const subscription = {
      endpoint: 'https://push.example/account-a-pending',
      toJSON: () => ({
        endpoint: 'https://push.example/account-a-pending',
        keys: { p256dh: 'key', auth: 'auth' },
      }),
      unsubscribe,
    };
    const postMessage = vi.fn();
    const registration = {
      active: { postMessage },
      pushManager: {
        getSubscription: vi.fn().mockImplementation(async () => current),
        subscribe: vi.fn().mockImplementation(async () => {
          current = subscription;
          return subscription;
        }),
      },
    };
    vi.stubGlobal('navigator', {
      serviceWorker: {
        controller: { postMessage },
        getRegistration: vi.fn().mockResolvedValue(registration),
        register: vi.fn().mockResolvedValue(registration),
      },
    });
    let finishSubscribe;
    const subscribePending = new Promise(resolve => { finishSubscribe = resolve; });
    const fetchMock = vi.fn((url) => {
      if (String(url).endsWith('/push/public_key')) {
        return Promise.resolve(response(200, { key: 'AQ' }));
      }
      if (String(url).endsWith('/push/subscribe')) return subscribePending;
      return Promise.resolve(response(200, { ok: true }));
    });
    vi.stubGlobal('fetch', fetchMock);
    const view = render(<PushToggle />);
    const enableButton = await screen.findByRole('button', { name: 'push.enable' });
    await waitFor(() => expect(enableButton).not.toBeDisabled());
    fireEvent.click(enableButton);
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => (
      String(url).endsWith('/push/subscribe')
    ))).toBe(true));

    await revokeAndClearSession();
    auth.user = null;
    view.rerender(<PushToggle />);
    finishSubscribe(response(200, { ok: true }));

    await waitFor(() => expect(unsubscribe).toHaveBeenCalledTimes(1));
    const compensating = fetchMock.mock.calls.find(([url, init]) => (
      String(url).endsWith('/push/unsubscribe')
      && init.headers.Authorization === `Bearer ${accountA}`
    ));
    expect(compensating).toBeDefined();
    expect(JSON.parse(compensating[1].body)).toEqual({
      endpoint: 'https://push.example/account-a-pending',
    });
    expect(postMessage.mock.calls.at(-1)[0]).toEqual(expect.objectContaining({
      type: 'SET_PUSH_ENABLED', enabled: false,
    }));
  });
});
