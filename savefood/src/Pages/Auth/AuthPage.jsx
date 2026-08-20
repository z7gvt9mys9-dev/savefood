import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../context/AuthContext';
import { API_URL, authFetch } from '../../api';
import { hasDeliveryLocation } from '../../utils/ticket';
import AddressInput from './AddressInput';
import './Auth.css';

const TELEGRAM_COMPLETION_RETRY_MS = 250;
const TELEGRAM_COMPLETION_MAX_ATTEMPTS = 5;
const NEEDY_REGISTRATION_KEY = 'savefood_needy_registration_id';

const AuthIcon = ({ name }) => {
  const paths = {
    telegram: <path d="M3 9.6 17 4l-3.2 12-4.5-3.4-2.8 2.2.6-4.1L14 6.5 7.1 10.7 3 9.6Z" />,
    business: <><path d="M3 9.5V18h14V9.5M2 8l2-4h12l2 4" /><path d="M2 8a2 2 0 0 0 4 0 2 2 0 0 0 4 0 2 2 0 0 0 4 0 2 2 0 0 0 4 0M8 18v-5h4v5" /></>,
    private: <><path d="m2.5 9.5 7.5-6 7.5 6" /><path d="M4.5 8v10h11V8M8 18v-5h4v5" /></>,
    info: <><circle cx="10" cy="10" r="7" /><path d="M10 9v5M10 6.5v.2" /></>,
  };
  return (
    <svg className={`auth-icon auth-icon-${name}`} viewBox="0 0 20 20" fill="none" aria-hidden="true">
      {paths[name]}
    </svg>
  );
};

const AuthPage = () => {
  const initialParams = new URLSearchParams(window.location.search);
  const requestedRole = initialParams.get('role');
  const startsInRegisterMode = initialParams.get('mode') === 'register';
  const allowedInitialRoles = startsInRegisterMode
    ? ['shop', 'volunteer', 'needy']
    : ['shop', 'volunteer', 'needy', 'admin'];
  const initialRole = allowedInitialRoles.includes(requestedRole) ? requestedRole : 'shop';
  const [isLogin, setIsLogin] = useState(!startsInRegisterMode);
  const [role, setRole] = useState(initialRole); // shop, volunteer, needy, admin
  const [step, setStep] = useState(1); // For multi-step registration (Needy)
  const [tgStep, setTgStep] = useState(false); // show Telegram link step after registration
  const [regAuthenticated, setRegAuthenticated] = useState(false);
  const [regSession, setRegSession] = useState(null);
  const [regNeedyId, setRegNeedyId] = useState(null);
  const [needySubmitting, setNeedySubmitting] = useState(false);
  // C2C: 'business' (магазин/кафе) | 'private' (частное лицо отдаёт излишки)
  const [donorKind, setDonorKind] = useState('business');

  const [formData, setFormData] = useState({
    email: '',
    password: '',
    phone: '',
    name: '',
    contact: '',
    legalData: '',
    address: '',
    lat: null,
    lon: null,
    familySize: 1,
    preferences: '',
    urgency: 'normal',
    apartment: '',
    floor_num: '',
    entrance: '',
  });

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    setFormData({ ...formData, [name]: value });
  };

  const handleAddressChange = (addr) => {
    setFormData(prev => ({
      ...prev,
      address: addr.address,
      lat: addr.lat,
      lon: addr.lon,
      city: addr.city,
      apartment: addr.apartment,
      floor_num: addr.floor_num,
      entrance: addr.entrance,
    }));
  };

  const { t } = useTranslation();
  const { user, login } = useAuth();
  const navigate = useNavigate();

  const [agreed, setAgreed] = useState(false);
  const [providers, setProviders] = useState(null);
  const [tgLogin, setTgLogin] = useState(null); // status-only browser transaction
  const [tgStarting, setTgStarting] = useState(false);
  const tgPollRef = useRef(null);
  const tgActiveTokenRef = useRef(null);
  const tgStartingRef = useRef(false);

  // Which social providers are configured on the server (hide the rest)
  useEffect(() => {
    fetch(`${API_URL}/auth/oauth/providers`)
      .then(r => r.ok ? r.json() : null)
      .then(data => { if (data) setProviders(data); })
      .catch(() => {});
    return () => {
      tgActiveTokenRef.current = null;
      tgStartingRef.current = false;
      clearInterval(tgPollRef.current);
    };
  }, []);

  // Keep a submitted registration resumable across reloads without storing the
  // password. The auth context already restores the owner's token.
  useEffect(() => {
    if (!startsInRegisterMode || initialRole !== 'needy' || regNeedyId) return;
    if (user?.role !== 'needy' || !user.relatedId) return;
    const savedId = Number(window.localStorage.getItem(NEEDY_REGISTRATION_KEY));
    if (savedId !== Number(user.relatedId)) return;
    setRegNeedyId(savedId);
    setRegAuthenticated(true);
    setRegSession({ role: 'needy', relatedId: savedId });
    setStep(2);
  }, [startsInRegisterMode, initialRole, regNeedyId, user]);

  // OAuth callbacks return a JWT in the fragment. Telegram instead returns a
  // one-time completion credential sent only in the private bot conversation.
  // Strip either fragment synchronously so it is not retained or replayed by a
  // second React StrictMode effect pass.
  useEffect(() => {
    const hash = new URLSearchParams(window.location.hash.slice(1));
    const telegramCompletion = hash.get('telegram_completion');
    const oauthError = hash.get('oauth_error');
    const oauthCompletion = hash.get('oauth_completion');
    const cleanLocation = `${window.location.pathname}${window.location.search}`;
    if (telegramCompletion) {
      window.history.replaceState(null, '', cleanLocation);
      const completeTelegramLogin = async () => {
        try {
          let res;
          for (let attempt = 0; attempt < TELEGRAM_COMPLETION_MAX_ATTEMPTS; attempt += 1) {
            res = await fetch(`${API_URL}/auth/telegram/login/complete`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ token: telegramCompletion }),
            });
            if (res.status !== 409 || attempt === TELEGRAM_COMPLETION_MAX_ATTEMPTS - 1) break;
            await new Promise(resolve => window.setTimeout(resolve, TELEGRAM_COMPLETION_RETRY_MS));
          }
          if (!res.ok) {
            alert(t('auth.tg_login_completion_error'));
            return;
          }
          const data = await res.json();
          if (!data.access_token || !data.refresh_token || !data.role) {
            alert(t('auth.tg_login_completion_error'));
            return;
          }
          login(data.access_token, data.refresh_token, data.role, data.related_id);
          navigate(`/${data.role}`);
        } catch {
          alert(t('common.connection_error'));
        }
      };
      completeTelegramLogin();
      return;
    }
    if (oauthError) {
      window.history.replaceState(null, '', cleanLocation);
      alert(decodeURIComponent(oauthError));
      return;
    }
    if (oauthCompletion) {
      window.history.replaceState(null, '', cleanLocation);
      const completeOAuthLogin = async () => {
        try {
          const res = await fetch(`${API_URL}/auth/oauth/login/complete`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token: oauthCompletion }),
          });
          if (!res.ok) {
            alert(t('auth.invalid_credentials'));
            return;
          }
          const data = await res.json();
          if (!data.access_token || !data.refresh_token || !data.role) {
            alert(t('auth.invalid_credentials'));
            return;
          }
          login(data.access_token, data.refresh_token, data.role, data.related_id);
          navigate(`/${data.role}`);
        } catch {
          alert(t('common.connection_error'));
        }
      };
      completeOAuthLogin();
    }
  }, []);

  const handleOAuthLogin = async (provider) => {
    try {
      const res = await fetch(`${API_URL}/auth/oauth/${provider}/start?mode=login`);
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        alert(err.detail || t('auth.invalid_credentials'));
        return;
      }
      const data = await res.json();
      window.location.href = data.url;
    } catch {
      alert(t('common.connection_error'));
    }
  };

  const handleTelegramLogin = async () => {
    if (tgStartingRef.current || tgActiveTokenRef.current) return;
    tgStartingRef.current = true;
    setTgStarting(true);
    try {
      const res = await fetch(`${API_URL}/auth/telegram/login/start`, { method: 'POST' });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        alert(err.detail || t('common.error'));
        return;
      }
      const data = await res.json();
      tgActiveTokenRef.current = data.token;
      setTgLogin({ ...data, status: 'pending' });
      window.open(data.link, '_blank', 'noopener');
      // This token observes status only. The JWT can be obtained solely by
      // opening the separate completion link delivered in private Telegram.
      clearInterval(tgPollRef.current);
      tgPollRef.current = setInterval(async () => {
        try {
          const poll = await fetch(`${API_URL}/auth/telegram/login/poll`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token: data.token }),
          });
          if (tgActiveTokenRef.current !== data.token) return;
          if (poll.status === 404) {
            clearInterval(tgPollRef.current);
            setTgLogin(prev => prev?.token === data.token ? { ...prev, status: 'expired' } : prev);
            return;
          }
          if (!poll.ok) return;
          const result = await poll.json();
          if (tgActiveTokenRef.current !== data.token) return;
          if (result.status === 'confirmed' || result.status === 'expired') {
            clearInterval(tgPollRef.current);
          }
          if (['pending', 'confirmed', 'expired'].includes(result.status)) {
            setTgLogin(prev => {
              if (prev?.token !== data.token) return prev;
              if (prev.status === 'confirmed' || prev.status === 'expired') return prev;
              return { ...prev, status: result.status };
            });
          }
        } catch {}
      }, 2500);
    } catch {
      alert(t('common.connection_error'));
    } finally {
      tgStartingRef.current = false;
      setTgStarting(false);
    }
  };

  const cancelTelegramLogin = async () => {
    const token = tgActiveTokenRef.current;
    tgActiveTokenRef.current = null;
    clearInterval(tgPollRef.current);
    tgPollRef.current = null;
    setTgLogin(null);
    if (!token) return;
    try {
      await fetch(`${API_URL}/auth/telegram/login/cancel`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token }),
      });
    } catch {}
  };

  const showRequestError = (error, fallbackKey) => {
    const isNetworkError = error?.name === 'TypeError';
    alert(isNetworkError ? t('common.connection_error') : (error?.message || t(fallbackKey)));
  };

  // The login screen lets one phone number be used as the identifier for
  // different account types. Never accept a session for a role other than the
  // one the person selected: otherwise an account with a matching identifier
  // could open the wrong dashboard.
  const acceptLogin = (data, expectedRole) => {
    if (!data.access_token || !data.refresh_token || data.role !== expectedRole) {
      throw new Error(t('auth.invalid_credentials'));
    }
    login(data.access_token, data.refresh_token, data.role, data.related_id);
    navigate(`/${data.role}`);
  };

  const appendLoginRole = (formData, expectedRole) => {
    formData.append('role', expectedRole);
    return formData;
  };

  const renderSocialLogin = () => {
    if (!providers || (!providers.google && !providers.yandex && !providers.telegram)) return null;
    return (
      <div style={{ marginTop: 16 }}>
        <p style={{ textAlign: 'center', color: '#888', fontSize: '0.85em', margin: '0 0 10px' }}>
          {t('auth.or_login_with')}
        </p>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'center', flexWrap: 'wrap' }}>
          {providers.google && (
            <button type="button" className="btn btn-secondary" onClick={() => handleOAuthLogin('google')}>
              G&nbsp;Google
            </button>
          )}
          {providers.yandex && (
            <button type="button" className="btn btn-secondary" onClick={() => handleOAuthLogin('yandex')}>
              Я&nbsp;Yandex
            </button>
          )}
          {providers.telegram && (
            <button
              type="button"
              className="btn btn-secondary"
              onClick={handleTelegramLogin}
              disabled={tgStarting || Boolean(tgLogin)}
            >
              <AuthIcon name="telegram" />Telegram
            </button>
          )}
        </div>
        {tgLogin && (
          <div style={{ textAlign: 'center', marginTop: 12 }}>
            <p style={{ color: '#aaa', fontSize: '0.85em' }}>
              {t(`auth.tg_login_${tgLogin.status}`)}
            </p>
            {tgLogin.status === 'pending' && (
              <a href={tgLogin.link} target="_blank" rel="noreferrer" style={{ color: '#5dade2' }}>
                {t('auth.tg_login_open_again')}
              </a>
            )}
            <button type="button" className="btn-small" style={{ marginLeft: 10 }} onClick={cancelTelegramLogin}>
              {t('common.cancel')}
            </button>
          </div>
        )}
      </div>
    );
  };

  const validateNeedyStep1 = () => {
    if (!formData.name || !formData.phone || !formData.password) {
      alert(t('auth.fill_required'));
      return false;
    }
    if (formData.password.length < 8) {
      alert(t('auth.password_min'));
      return false;
    }
    if (!agreed) {
      alert(t('auth.agree_required'));
      return false;
    }
    return true;
  };

  const submitNeedyStep1 = async () => {
    if (needySubmitting || !validateNeedyStep1()) return;
    setNeedySubmitting(true);
    try {
      let needyId = regNeedyId;
      if (!needyId) {
        const registerRes = await fetch(`${API_URL}/needy/register`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            name: formData.name,
            contact: formData.phone,
            username: formData.phone,
            password: formData.password,
          }),
        });
        if (!registerRes.ok) {
          const err = await registerRes.json().catch(() => null);
          throw new Error(err?.detail || t('auth.register_error'));
        }
        const registered = await registerRes.json();
        needyId = registered.id;
        if (!needyId) throw new Error(t('auth.register_error'));
        setRegNeedyId(needyId);
      }

      if (!regAuthenticated) {
        const fd = new FormData();
        fd.append('username', formData.phone);
        fd.append('password', formData.password);
        appendLoginRole(fd, 'needy');
        const loginRes = await fetch(`${API_URL}/auth/login`, { method: 'POST', body: fd });
        if (!loginRes.ok) throw new Error(t('auth.login_after_register_error'));
        const loginData = await loginRes.json();
        if (!loginData.access_token || !loginData.refresh_token) {
          throw new Error(t('auth.login_after_register_error'));
        }
        const sessionRole = loginData.role || 'needy';
        const relatedId = loginData.related_id ?? needyId;
        setRegAuthenticated(true);
        setRegSession({ role: sessionRole, relatedId });
        login(loginData.access_token, loginData.refresh_token, sessionRole, relatedId);
      }

      window.localStorage.setItem(NEEDY_REGISTRATION_KEY, String(needyId));
      setStep(2);
    } catch (err) {
      showRequestError(err, 'auth.register_error');
    } finally {
      setNeedySubmitting(false);
    }
  };

  const submitNeedyProfile = async (e) => {
    e.preventDefault();
    if (!regNeedyId || !regAuthenticated) {
      alert(t('auth.registration_session_error'));
      return;
    }
    if (!hasDeliveryLocation(formData)) {
      alert(t('auth.delivery_location_required'));
      return;
    }
    setNeedySubmitting(true);
    try {
      const res = await authFetch(`${API_URL}/needy/${regNeedyId}/profile`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          address: formData.address || null,
          family_size: formData.familySize ? Number(formData.familySize) : null,
          preferences: formData.preferences || null,
          urgency: formData.urgency || 'normal',
          city: formData.city || null,
          lat: formData.lat ?? null,
          lon: formData.lon ?? null,
          apartment: formData.apartment || null,
          floor_num: formData.floor_num || null,
          entrance: formData.entrance || null,
        }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => null);
        throw new Error(err?.detail || t('auth.profile_save_error'));
      }
      window.localStorage.removeItem(NEEDY_REGISTRATION_KEY);
      setTgStep(true);
    } catch (err) {
      showRequestError(err, 'auth.profile_save_error');
    } finally {
      setNeedySubmitting(false);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!isLogin && !agreed) {
      alert(t('auth.agree_required'));
      return;
    }

    if (isLogin) {
      try {
        const fd = new FormData();
        fd.append('username', (role === 'shop' || role === 'admin') ? formData.email : formData.phone);
        fd.append('password', formData.password);
        appendLoginRole(fd, role);
        const res = await fetch(`${API_URL}/auth/login`, { method: 'POST', body: fd });
        if (!res.ok) throw new Error(t('auth.invalid_credentials'));
        const data = await res.json();
        acceptLogin(data, role);
      } catch (err) {
        showRequestError(err, 'auth.invalid_credentials');
      }
    } else {
      try {
        // The final needy-registration step collects an actual delivery point.
        // A manually typed address has no trustworthy coordinates until it is
        // selected from the geocoder, so do not create a profile that cannot be
        // served by a volunteer.
        let endpoint = '';
        let body = {};
        if (role === 'shop') {
          endpoint = `${API_URL}/shops/register`;
          body = { name: formData.name, contact: formData.contact, lat: formData.lat, lon: formData.lon, city: formData.city, username: formData.email, password: formData.password, kind: donorKind };
        } else if (role === 'volunteer') {
          endpoint = `${API_URL}/volunteers/register`;
          body = { name: formData.name, contact: formData.phone, city: formData.city, lat: formData.lat, lon: formData.lon, username: formData.phone, password: formData.password };
        } else return;
        const res = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        if (!res.ok) {
          const err = await res.json().catch(() => null);
          throw new Error(err?.detail || t('auth.register_error'));
        }
        const data = await res.json();

        // Auto-login first so owner-protected profile writes carry a valid token
        // (those endpoints are owner-protected).
        let token = null;
        try {
          const loginUsername = (role === 'shop') ? formData.email : formData.phone;
          const fd = new FormData();
          fd.append('username', loginUsername);
          fd.append('password', formData.password);
          appendLoginRole(fd, role);
          const loginRes = await fetch(`${API_URL}/auth/login`, { method: 'POST', body: fd });
          if (loginRes.ok) {
            const loginData = await loginRes.json();
            token = loginData.access_token;
            if (!token || !loginData.refresh_token) throw new Error('Incomplete authentication response');
            setRegAuthenticated(true);
            const sessionRole = loginData.role || role;
            const relatedId = loginData.related_id ?? data.id ?? null;
            // Registration already performed a successful login. Persist that
            // session just like the normal login flow rather than making the
            // new account holder authenticate a second time after Telegram.
            login(token, loginData.refresh_token, sessionRole, relatedId);
            setRegSession({ role: sessionRole, relatedId });
          }
        } catch {}

        setTgStep(true);
      } catch (err) {
        showRequestError(err, 'auth.register_error');
      }
    }
  };

  const renderRoleSelection = () => (
    <div className="role-selector">
      <button
        className={`role-btn ${role === 'shop' ? 'active' : ''}`}
        onClick={() => { setRole('shop'); setStep(1); }}
      >
        {t('auth.role_shop')}
      </button>
      <button
        className={`role-btn ${role === 'volunteer' ? 'active' : ''}`}
        onClick={() => { setRole('volunteer'); setStep(1); }}
      >
        {t('auth.role_volunteer')}
      </button>
      <button
        className={`role-btn ${role === 'needy' ? 'active' : ''}`}
        onClick={() => { setRole('needy'); setStep(1); }}
      >
        {t('auth.role_needy')}
      </button>
    </div>
  );

  const renderLoginForm = () => (
    <form onSubmit={handleSubmit} className="auth-form">
      <h2>{t('auth.login')}: {t(`auth.role_${role}`)}</h2>

      {role === 'shop' || role === 'admin' ? (
        <>
          <input type="email" name="email" placeholder={t('auth.email')} onChange={handleInputChange} required />
          <input type="password" name="password" placeholder={t('auth.password')} onChange={handleInputChange} required />
        </>
      ) : (
        <>
          <input type="tel" name="phone" placeholder={t('auth.phone')} onChange={handleInputChange} required />
          <input type="password" name="password" placeholder={t('auth.password')} onChange={handleInputChange} required />
        </>
      )}

      <button type="submit" className="btn btn-primary">{t('auth.submit_login')}</button>
      {renderSocialLogin()}
      {role !== 'admin' && (
        <p onClick={() => setIsLogin(false)} className="toggle-auth">{t('auth.switch_to_register')}</p>
      )}
    </form>
  );

  const renderShopReg = () => (
    <form onSubmit={handleSubmit} className="auth-form">
      <h2>{t('auth.register')}: {donorKind === 'private' ? t('auth.donor_private') : t('auth.role_shop')}</h2>
      <div className="donor-kind-selector">
        {['business', 'private'].map(kind => (
          <button
            key={kind}
            type="button"
            className={`donor-kind-btn ${donorKind === kind ? 'active' : ''}`}
            aria-pressed={donorKind === kind}
            onClick={() => setDonorKind(kind)}
          >
            <AuthIcon name={kind === 'business' ? 'business' : 'private'} />
            {kind === 'business' ? t('auth.donor_business') : t('auth.donor_private')}
          </button>
        ))}
      </div>
      {donorKind === 'private' && (
        <p style={{ fontSize: '0.8rem', color: '#aaa', margin: '0 0 8px' }}>{t('auth.donor_private_hint')}</p>
      )}
      <input type="text" name="name" placeholder={donorKind === 'private' ? t('auth.full_name') : t('auth.name')} onChange={handleInputChange} required />
      {donorKind === 'business' && (
        <input type="text" name="legalData" placeholder={t('auth.legal')} onChange={handleInputChange} required />
      )}
      <input type="text" name="contact" placeholder={t('auth.contact')} onChange={handleInputChange} required />
      <input type="email" name="email" placeholder={t('auth.email')} onChange={handleInputChange} required />
      <input type="password" name="password" placeholder={t('auth.password')} onChange={handleInputChange} minLength={8} required />

      <AddressInput
        label={t('auth.address')}
        onChange={handleAddressChange}
        value={formData.address}
        lat={formData.lat}
        lon={formData.lon}
        city={formData.city}
        apartment={formData.apartment}
        floorNum={formData.floor_num}
        entrance={formData.entrance}
        showUnitFields={false}
      />

      <div className="consent-box">
        <label className="checkbox-label">
          <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} required />
          <span>{t('auth.agree')} (<a href="/privacy">{t('auth.privacy')}</a>)</span>
        </label>
      </div>

      <button type="submit" className="btn btn-primary">{t('auth.submit_register')}</button>
      <p onClick={() => setIsLogin(true)} className="toggle-auth">{t('auth.switch_to_login')}</p>
    </form>
  );

  const renderVolunteerReg = () => (
    <form onSubmit={handleSubmit} className="auth-form">
      <h2>{t('auth.register')}: {t('auth.role_volunteer')}</h2>
      <input type="text" name="name" placeholder={t('auth.name')} onChange={handleInputChange} required />
      <input type="tel" name="phone" placeholder={t('auth.phone')} onChange={handleInputChange} required />
      <input type="password" name="password" placeholder={t('auth.password')} onChange={handleInputChange} minLength={8} required />

      <AddressInput
        label={t('volunteer.your_city')}
        onChange={handleAddressChange}
        value={formData.address}
        lat={formData.lat}
        lon={formData.lon}
        city={formData.city}
        apartment={formData.apartment}
        floorNum={formData.floor_num}
        entrance={formData.entrance}
      />

      <div className="consent-box">
        <label className="checkbox-label">
          <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} required />
          <span>{t('auth.agree')}</span>
        </label>
      </div>

      <button type="submit" className="btn btn-primary">{t('auth.submit_register')}</button>
      <p onClick={() => setIsLogin(true)} className="toggle-auth">{t('auth.switch_to_login')}</p>
    </form>
  );

  const renderNeedyReg = () => {
    if (step === 1) {
      return (
        <div className="auth-form">
          <h2>{t('auth.needy_step1_title')}</h2>
          <p className="subtitle">{t('auth.needy_step1_subtitle')}</p>
          <input type="text" name="name" placeholder={t('auth.full_name')} onChange={handleInputChange} required />
          <input type="tel" name="phone" placeholder={t('auth.phone_number')} onChange={handleInputChange} required />
          <input type="password" name="password" placeholder={t('auth.create_password')} onChange={handleInputChange} minLength={8} required />

          <div className="consent-box">
            <label className="checkbox-label">
              <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} required />
              <span>{t('auth.agree')}</span>
            </label>
          </div>

          <button type="button" onClick={submitNeedyStep1} className="btn btn-primary" disabled={needySubmitting}>
            {needySubmitting ? t('common.loading') : t('auth.next')}
          </button>
          <p onClick={() => setIsLogin(true)} className="toggle-auth">{t('auth.switch_to_login')}</p>
        </div>
      );
    }
    return (
      <form onSubmit={submitNeedyProfile} className="auth-form">
        <h2>{t('auth.needy_step2_title')}</h2>
        <AddressInput
          label={t('auth.home_address')}
          onChange={handleAddressChange}
          value={formData.address}
          lat={formData.lat}
          lon={formData.lon}
          city={formData.city}
          apartment={formData.apartment}
          floorNum={formData.floor_num}
          entrance={formData.entrance}
        />
        <input type="number" name="familySize" placeholder={t('auth.family_members')} onChange={handleInputChange} required />
        <textarea name="preferences" placeholder={t('auth.dietary_prefs')} onChange={handleInputChange}></textarea>

        <label>{t('auth.urgency_label')}</label>
        <select name="urgency" onChange={handleInputChange}>
          <option value="normal">{t('auth.urgency_normal')}</option>
          <option value="high">{t('auth.urgency_high')}</option>
          <option value="critical">{t('auth.urgency_critical')}</option>
        </select>

        <div className="limit-notice">
          <p><AuthIcon name="info" />{t('auth.limit_notice')}</p>
        </div>

        <button type="submit" className="btn btn-primary" disabled={needySubmitting}>
          {needySubmitting ? t('common.loading') : t('auth.finish_register')}
        </button>
      </form>
    );
  };

  const renderTgStep = () => (
    <div className="auth-form">
      <h2>{t('auth.telegram_title')}</h2>
      <p style={{ color: '#aaa', marginBottom: 20 }}>{t('auth.telegram_desc')}</p>
      {regAuthenticated && (
        <button
          className="btn btn-primary"
          style={{ marginBottom: 12 }}
          onClick={async () => {
            try {
              const res = await authFetch(`${API_URL}/auth/telegram/init-link`, {
              });
              if (!res.ok) { alert(t('auth.error_tg')); return; }
              const data = await res.json();
              window.open(data.link, '_blank');
            } catch { alert(t('common.connection_error')); }
          }}
        >
          {t('auth.telegram_open')}
        </button>
      )}
      <button
        className="btn btn-secondary"
        onClick={() => {
          if (regSession?.role) {
            navigate(`/${regSession.role}`);
            return;
          }
          setTgStep(false);
          setIsLogin(true);
        }}
      >
        {t('auth.telegram_skip')}
      </button>
    </div>
  );

  return (
    <div className="auth-container">
      <div className="auth-card">
        {!tgStep && renderRoleSelection()}
        {tgStep ? renderTgStep() : (
          isLogin ? renderLoginForm() : (
            role === 'shop' ? renderShopReg() :
            role === 'volunteer' ? renderVolunteerReg() :
            renderNeedyReg()
          )
        )}
      </div>
    </div>
  );
};

export default AuthPage;
