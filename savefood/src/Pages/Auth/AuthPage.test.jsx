import React from 'react';
import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import AuthPage from './AuthPage';

const { loginMock } = vi.hoisted(() => ({ loginMock: vi.fn() }));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

vi.mock('../../context/AuthContext', () => ({
  useAuth: () => ({ user: null, login: loginMock }),
}));

vi.mock('./AddressInput', () => ({
  default: ({ showUnitFields, onChange }) => (
    <div data-testid="address-input" data-show-unit-fields={String(showUnitFields)}>
      <button type="button" onClick={() => onChange({
        address: 'Москва, Тверская улица, 1',
        city: 'Москва',
        lat: 55.75,
        lon: 37.61,
        apartment: '1',
        floor_num: '2',
        entrance: '3',
      })}>
        test.select_address
      </button>
    </div>
  ),
}));

const jsonResponse = (data, ok = true, status = ok ? 200 : 400) => ({
  ok,
  status,
  json: vi.fn().mockResolvedValue(data),
});

const renderPage = (query = '') => {
  window.history.replaceState({}, '', `/auth${query}`);
  return render(
    <MemoryRouter>
      <AuthPage />
    </MemoryRouter>
  );
};

const fillShopRegistration = () => {
  const nameInput = screen.queryByPlaceholderText('auth.name') || screen.getByPlaceholderText('auth.full_name');
  fireEvent.change(nameInput, { target: { value: 'Добрый магазин' } });
  const legalInput = screen.queryByPlaceholderText('auth.legal');
  if (legalInput) fireEvent.change(legalInput, { target: { value: 'ООО Добро' } });
  fireEvent.change(screen.getByPlaceholderText('auth.contact'), { target: { value: '+79990000000' } });
  fireEvent.change(screen.getByPlaceholderText('auth.email'), { target: { value: 'shop@example.com' } });
  fireEvent.change(screen.getByPlaceholderText('auth.password'), { target: { value: 'password123' } });
  fireEvent.click(screen.getByRole('checkbox'));
};

const fillNeedyStep1 = () => {
  const document = new File(['document'], 'document.pdf', { type: 'application/pdf' });
  fireEvent.change(screen.getByPlaceholderText('auth.full_name'), { target: { value: 'Иван Иванов' } });
  fireEvent.change(screen.getByPlaceholderText('auth.phone_number'), { target: { value: '+79990000000' } });
  fireEvent.change(screen.getByPlaceholderText('auth.create_password'), { target: { value: 'password123' } });
  fireEvent.change(screen.getByLabelText('auth.document_status'), { target: { files: [document] } });
  fireEvent.click(screen.getByRole('checkbox'));
  return document;
};

describe('AuthPage registration', () => {
  let fetchMock;
  let alertMock;

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue(jsonResponse({}, false));
    vi.stubGlobal('fetch', fetchMock);
    alertMock = vi.spyOn(window, 'alert').mockImplementation(() => {});
    loginMock.mockReset();
    window.localStorage.clear();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    window.history.replaceState({}, '', '/');
  });

  it('does not offer administrator as a public registration role', () => {
    renderPage('?mode=register');

    expect(screen.queryByRole('button', { name: 'auth.role_admin' })).toBeNull();
    expect(screen.getByText('auth.register: auth.role_shop')).toBeTruthy();
  });

  it('does not offer administrator in the public sign-in role list', () => {
    renderPage();

    expect(screen.queryByRole('button', { name: 'auth.role_admin' })).toBeNull();
  });

  it('keeps direct administrator sign in available without a public role button', () => {
    renderPage('?mode=login&role=admin');

    expect(screen.getByText('auth.login: auth.role_admin')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'auth.role_admin' })).toBeNull();
    expect(screen.getByPlaceholderText('auth.email')).toBeTruthy();
  });

  it('sends the selected role when a volunteer signs in', async () => {
    fetchMock.mockImplementation((url) => {
      if (String(url).endsWith('/auth/oauth/providers')) return Promise.resolve(jsonResponse({}, false));
      if (String(url).endsWith('/auth/login')) {
        return Promise.resolve(jsonResponse({ access_token: 'volunteer-token', role: 'volunteer', related_id: 7 }));
      }
      return Promise.resolve(jsonResponse({}, false));
    });
    renderPage('?role=volunteer');
    fireEvent.change(screen.getByPlaceholderText('auth.phone'), { target: { value: '+79990000000' } });
    fireEvent.change(screen.getByPlaceholderText('auth.password'), { target: { value: 'password123' } });
    fireEvent.click(screen.getByRole('button', { name: 'auth.submit_login' }));

    await waitFor(() => expect(loginMock).toHaveBeenCalledWith('volunteer-token', 'volunteer', 7));
    const loginCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/auth/login'));
    expect(loginCall[1].body.get('role')).toBe('volunteer');
  });

  it('does not accept a session for another role', async () => {
    fetchMock.mockImplementation((url) => {
      if (String(url).endsWith('/auth/oauth/providers')) return Promise.resolve(jsonResponse({}, false));
      if (String(url).endsWith('/auth/login')) {
        return Promise.resolve(jsonResponse({ access_token: 'needy-token', role: 'needy', related_id: 42 }));
      }
      return Promise.resolve(jsonResponse({}, false));
    });
    renderPage('?role=volunteer');
    fireEvent.change(screen.getByPlaceholderText('auth.phone'), { target: { value: '+79990000000' } });
    fireEvent.change(screen.getByPlaceholderText('auth.password'), { target: { value: 'password123' } });
    fireEvent.click(screen.getByRole('button', { name: 'auth.submit_login' }));

    await waitFor(() => expect(alertMock).toHaveBeenCalledWith('auth.invalid_credentials'));
    expect(loginMock).not.toHaveBeenCalled();
  });

  it('falls back to shop for an administrator registration deep link', () => {
    renderPage('?mode=register&role=admin');

    expect(screen.queryByRole('button', { name: 'auth.role_admin' })).toBeNull();
    expect(screen.getByText('auth.register: auth.role_shop')).toBeTruthy();
    expect(screen.queryByText('auth.needy_step1_title')).toBeNull();
  });

  it('creates the recipient, signs in and uploads the document before showing moderation', async () => {
    fetchMock.mockImplementation((url) => {
      const path = String(url);
      if (path.endsWith('/auth/oauth/providers')) return Promise.resolve(jsonResponse({}, false));
      if (path.endsWith('/needy/register')) return Promise.resolve(jsonResponse({ id: 42 }));
      if (path.endsWith('/auth/login')) {
        return Promise.resolve(jsonResponse({ access_token: 'needy-token', role: 'needy', related_id: 42 }));
      }
      if (path.endsWith('/needy/42/profile/upload')) return Promise.resolve(jsonResponse({ needy_id: 42 }));
      if (path.endsWith('/needy/42')) return Promise.resolve(jsonResponse({ id: 42, status: 'pending' }));
      return Promise.resolve(jsonResponse({}, false));
    });
    renderPage('?mode=register&role=needy');
    const document = fillNeedyStep1();
    fireEvent.click(screen.getByRole('button', { name: 'auth.next' }));

    await waitFor(() => expect(screen.getByText('auth.needy_step2_title')).toBeTruthy());
    const registrationCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/needy/register'));
    expect(JSON.parse(registrationCall[1].body)).toEqual({
      name: 'Иван Иванов',
      contact: '+79990000000',
      username: '+79990000000',
      password: 'password123',
    });
    const loginCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/auth/login'));
    expect(loginCall[1].body.get('username')).toBe('+79990000000');
    expect(loginCall[1].body.get('password')).toBe('password123');
    expect(loginCall[1].body.get('role')).toBe('needy');
    const uploadCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/needy/42/profile/upload'));
    expect(uploadCall[1].headers.Authorization).toBe('Bearer needy-token');
    expect(uploadCall[1].body.get('file')).toBe(document);
    expect(loginMock).toHaveBeenCalledWith('needy-token', 'needy', 42);
    expect(screen.queryByRole('button', { name: 'auth.simulate_approve' })).toBeNull();
  });

  it('opens step 3 after approval and saves only the existing recipient profile', async () => {
    fetchMock.mockImplementation((url) => {
      const path = String(url);
      if (path.endsWith('/auth/oauth/providers')) return Promise.resolve(jsonResponse({}, false));
      if (path.endsWith('/needy/register')) return Promise.resolve(jsonResponse({ id: 42 }));
      if (path.endsWith('/auth/login')) {
        return Promise.resolve(jsonResponse({ access_token: 'needy-token', role: 'needy', related_id: 42 }));
      }
      if (path.endsWith('/needy/42/profile/upload')) return Promise.resolve(jsonResponse({ needy_id: 42 }));
      if (path.endsWith('/needy/42/profile')) return Promise.resolve(jsonResponse({ needy_id: 42 }));
      if (path.endsWith('/needy/42')) return Promise.resolve(jsonResponse({ id: 42, status: 'approved' }));
      return Promise.resolve(jsonResponse({}, false));
    });
    renderPage('?mode=register&role=needy');
    fillNeedyStep1();
    fireEvent.click(screen.getByRole('button', { name: 'auth.next' }));

    await waitFor(() => expect(screen.getByText('auth.needy_step3_title')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'test.select_address' }));
    fireEvent.change(screen.getByPlaceholderText('auth.family_members'), { target: { value: '4' } });
    fireEvent.change(screen.getByPlaceholderText('auth.dietary_prefs'), { target: { value: 'Без орехов' } });
    fireEvent.click(screen.getByRole('button', { name: 'auth.finish_register' }));

    await waitFor(() => expect(screen.getByText('auth.telegram_title')).toBeTruthy());
    const profileCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/needy/42/profile'));
    expect(profileCall[1].headers.Authorization).toBe('Bearer needy-token');
    expect(JSON.parse(profileCall[1].body)).toMatchObject({
      address: 'Москва, Тверская улица, 1',
      city: 'Москва',
      lat: 55.75,
      lon: 37.61,
      family_size: 4,
      preferences: 'Без орехов',
    });
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/needy/register'))).toHaveLength(1);
  });

  it('retries a failed document upload without creating the recipient twice', async () => {
    let uploadAttempts = 0;
    fetchMock.mockImplementation((url) => {
      const path = String(url);
      if (path.endsWith('/auth/oauth/providers')) return Promise.resolve(jsonResponse({}, false));
      if (path.endsWith('/needy/register')) return Promise.resolve(jsonResponse({ id: 42 }));
      if (path.endsWith('/auth/login')) {
        return Promise.resolve(jsonResponse({ access_token: 'needy-token', role: 'needy', related_id: 42 }));
      }
      if (path.endsWith('/needy/42/profile/upload')) {
        uploadAttempts += 1;
        return Promise.resolve(uploadAttempts === 1
          ? jsonResponse({ detail: 'upload failed' }, false)
          : jsonResponse({ needy_id: 42 }));
      }
      if (path.endsWith('/needy/42')) return Promise.resolve(jsonResponse({ id: 42, status: 'pending' }));
      return Promise.resolve(jsonResponse({}, false));
    });
    renderPage('?mode=register&role=needy');
    fillNeedyStep1();
    fireEvent.click(screen.getByRole('button', { name: 'auth.next' }));

    await waitFor(() => expect(alertMock).toHaveBeenCalledWith('upload failed'));
    fireEvent.click(screen.getByRole('button', { name: 'auth.next' }));
    await waitFor(() => expect(screen.getByText('auth.needy_step2_title')).toBeTruthy());

    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/needy/register'))).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/auth/login'))).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/needy/42/profile/upload'))).toHaveLength(2);
  });

  it('uses compact, stateful buttons for donor kind', () => {
    renderPage('?mode=register&role=shop');
    const business = screen.getByRole('button', { name: 'auth.donor_business' });
    const privateDonor = screen.getByRole('button', { name: 'auth.donor_private' });

    expect(business).toHaveClass('donor-kind-btn', 'active');
    expect(business).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(privateDonor);
    expect(privateDonor).toHaveClass('active');
    expect(privateDonor).toHaveAttribute('aria-pressed', 'true');
    expect(business).toHaveAttribute('aria-pressed', 'false');
  });

  it('hides residential unit fields from shop registration', () => {
    renderPage('?mode=register&role=shop');

    expect(screen.getByTestId('address-input')).toHaveAttribute('data-show-unit-fields', 'false');
  });

  it('shows a localized connection error instead of Safari Load failed', async () => {
    fetchMock.mockImplementation((url) => {
      if (String(url).endsWith('/auth/oauth/providers')) return Promise.resolve(jsonResponse({}, false));
      if (String(url).endsWith('/shops/register')) return Promise.reject(new TypeError('Load failed'));
      return Promise.resolve(jsonResponse({}));
    });
    renderPage('?mode=register&role=shop');
    fillShopRegistration();

    fireEvent.click(screen.getByRole('button', { name: 'auth.submit_register' }));

    await waitFor(() => expect(alertMock).toHaveBeenCalledWith('common.connection_error'));
  });

  it('uses the registration fallback for a non-JSON server error', async () => {
    fetchMock.mockImplementation((url) => {
      if (String(url).endsWith('/auth/oauth/providers')) return Promise.resolve(jsonResponse({}, false));
      if (String(url).endsWith('/shops/register')) {
        return Promise.resolve({ ok: false, json: vi.fn().mockRejectedValue(new SyntaxError('Unexpected token')) });
      }
      return Promise.resolve(jsonResponse({}));
    });
    renderPage('?mode=register&role=shop');
    fillShopRegistration();

    fireEvent.click(screen.getByRole('button', { name: 'auth.submit_register' }));

    await waitFor(() => expect(alertMock).toHaveBeenCalledWith('auth.register_error'));
  });

  it.each([
    ['business', 'auth.donor_business'],
    ['private', 'auth.donor_private'],
  ])('sends the selected %s donor kind to shop registration', async (kind, label) => {
    fetchMock.mockImplementation((url) => {
      if (String(url).endsWith('/auth/oauth/providers')) return Promise.resolve(jsonResponse({}, false));
      if (String(url).endsWith('/shops/register')) return Promise.resolve(jsonResponse({ id: 7 }));
      if (String(url).endsWith('/auth/login')) return Promise.resolve(jsonResponse({}, false));
      return Promise.resolve(jsonResponse({}, false));
    });
    renderPage('?mode=register&role=shop');
    fireEvent.click(screen.getByRole('button', { name: label }));
    fillShopRegistration();

    fireEvent.click(screen.getByRole('button', { name: 'auth.submit_register' }));

    await waitFor(() => {
      const registrationCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/shops/register'));
      expect(registrationCall).toBeTruthy();
      expect(registrationCall[1].method).toBe('POST');
      expect(JSON.parse(registrationCall[1].body).kind).toBe(kind);
    });
  });
});
