import React from 'react';
import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SESSION_STORAGE_KEY } from '../api';
import { AuthProvider, useAuth } from './AuthContext';

const SessionProbe = () => {
  const { user, login, logout, loading } = useAuth();
  if (loading) return <span>loading</span>;
  return (
    <div>
      <span>{user ? `${user.role}:${user.token}` : 'signed-out'}</span>
      <button type="button" onClick={() => login('access-1', 'refresh-1', 'shop', 8)}>login</button>
      <button type="button" onClick={logout}>logout</button>
    </div>
  );
};

describe('AuthContext token-pair compatibility', () => {
  beforeEach(() => {
    window.localStorage.clear();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200 }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('persists both login credentials and revokes/clears them on logout', async () => {
    render(<AuthProvider><SessionProbe /></AuthProvider>);
    await screen.findByText('signed-out');

    fireEvent.click(screen.getByRole('button', { name: 'login' }));
    expect(await screen.findByText('shop:access-1')).toBeTruthy();
    expect(JSON.parse(window.localStorage.getItem(SESSION_STORAGE_KEY))).toMatchObject({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
    });

    fireEvent.click(screen.getByRole('button', { name: 'logout' }));
    await screen.findByText('signed-out');
    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/auth/logout', expect.objectContaining({
      body: JSON.stringify({ refresh_token: 'refresh-1' }),
    })));
    expect(window.localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });
});
