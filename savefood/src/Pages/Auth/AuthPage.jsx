import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../context/AuthContext';
import { API_URL } from '../../api';
import AddressInput from './AddressInput';
import './Auth.css';

const AuthPage = () => {
  const [isLogin, setIsLogin] = useState(true);
  const [role, setRole] = useState('shop'); // shop, volunteer, needy
  const [step, setStep] = useState(1); // For multi-step registration (Needy)
  const [tgStep, setTgStep] = useState(false); // show Telegram link step after registration
  const [regToken, setRegToken] = useState(null); // token after registration

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
    document: null
  });

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    setFormData({ ...formData, [name]: value });
  };

  const handleAddressChange = (addr) => {
    setFormData({ ...formData, address: addr.address, lat: addr.lat, lon: addr.lon, city: addr.city });
  };

  const { t } = useTranslation();
  const { login } = useAuth();
  const navigate = useNavigate();

  const [agreed, setAgreed] = useState(false);

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
        const res = await fetch(`${API_URL}/auth/login`, { method: 'POST', body: fd });
        if (!res.ok) throw new Error(t('auth.invalid_credentials'));
        const data = await res.json();
        login(data.access_token, data.role, data.related_id);
        navigate(`/${data.role}`);
      } catch (err) {
        alert(err.message);
      }
    } else {
      try {
        let endpoint = '';
        let body = {};
        if (role === 'shop') {
          endpoint = `${API_URL}/shops/register`;
          body = { name: formData.name, contact: formData.contact, lat: formData.lat, lon: formData.lon, city: formData.city, username: formData.email, password: formData.password };
        } else if (role === 'volunteer') {
          endpoint = `${API_URL}/volunteers/register`;
          body = { name: formData.name, contact: formData.phone, city: formData.city, username: formData.phone, password: formData.password };
        } else {
          endpoint = `${API_URL}/needy/register`;
          body = { name: formData.name, contact: formData.phone, username: formData.phone, password: formData.password };
        }
        const res = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        if (!res.ok) {
          const err = await res.json();
          throw new Error(err.detail || t('auth.register_error'));
        }
        const data = await res.json();

        // Auto-login first so profile/document uploads carry a valid token
        // (those endpoints are owner-protected).
        let token = null;
        try {
          const loginUsername = (role === 'shop') ? formData.email : formData.phone;
          const fd = new FormData();
          fd.append('username', loginUsername);
          fd.append('password', formData.password);
          const loginRes = await fetch(`${API_URL}/auth/login`, { method: 'POST', body: fd });
          if (loginRes.ok) {
            const loginData = await loginRes.json();
            token = loginData.access_token;
            setRegToken(token);
          }
        } catch {}

        if (role === 'needy' && data.id && token) {
          const authHeader = { Authorization: `Bearer ${token}` };
          if (formData.document) {
            const fd = new FormData();
            fd.append('file', formData.document);
            await fetch(`${API_URL}/needy/${data.id}/profile/upload`, {
              method: 'POST',
              headers: authHeader,
              body: fd,
            }).catch(() => {});
          }
          // Persist the step-3 profile (address/coords/family/urgency) so delivery
          // tickets carry the recipient's coordinates without re-entering everything.
          await fetch(`${API_URL}/needy/${data.id}/profile`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', ...authHeader },
            body: JSON.stringify({
              address: formData.address || null,
              family_size: formData.familySize ? Number(formData.familySize) : null,
              preferences: formData.preferences || null,
              urgency: formData.urgency || 'normal',
              city: formData.city || null,
              lat: formData.lat ?? null,
              lon: formData.lon ?? null,
            }),
          }).catch(() => {});
        }
        setTgStep(true);
      } catch (err) {
        alert(err.message);
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
      <button
        className={`role-btn ${role === 'admin' ? 'active' : ''}`}
        onClick={() => { setRole('admin'); setStep(1); }}
      >
        {t('auth.role_admin')}
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
      {role !== 'admin' && (
        <p onClick={() => setIsLogin(false)} className="toggle-auth">{t('auth.switch_to_register')}</p>
      )}
    </form>
  );

  const renderShopReg = () => (
    <form onSubmit={handleSubmit} className="auth-form">
      <h2>{t('auth.register')}: {t('auth.role_shop')}</h2>
      <input type="text" name="name" placeholder={t('auth.name')} onChange={handleInputChange} required />
      <input type="text" name="legalData" placeholder={t('auth.legal')} onChange={handleInputChange} required />
      <input type="text" name="contact" placeholder={t('auth.contact')} onChange={handleInputChange} required />
      <input type="email" name="email" placeholder={t('auth.email')} onChange={handleInputChange} required />
      <input type="password" name="password" placeholder={t('auth.password')} onChange={handleInputChange} required />

      <AddressInput
        label={t('auth.address')}
        onChange={handleAddressChange}
        value={formData.address}
      />

      <div className="consent-box">
        <label className="checkbox-label">
          <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} required />
          <span>{t('auth.agree')} (<a href="#" onClick={(e) => { e.preventDefault(); alert(t('auth.privacy_text')); }}>{t('auth.privacy')}</a>)</span>
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
      <input type="password" name="password" placeholder={t('auth.password')} onChange={handleInputChange} required />

      <AddressInput
        label={t('volunteer.your_city')}
        onChange={handleAddressChange}
        value={formData.address}
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
          <input type="password" name="password" placeholder={t('auth.create_password')} onChange={handleInputChange} required />

          <div className="file-upload">
            <label>{t('auth.document_status')}</label>
            <input type="file" onChange={(e) => setFormData({...formData, document: e.target.files[0]})} required />
          </div>

          <div className="consent-box">
            <label className="checkbox-label">
              <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} required />
              <span>{t('auth.consent_docs')}</span>
            </label>
          </div>

          <button onClick={() => setStep(2)} className="btn btn-primary">{t('auth.next')}</button>
          <p onClick={() => setIsLogin(true)} className="toggle-auth">{t('auth.switch_to_login')}</p>
        </div>
      );
    }
    if (step === 2) {
      return (
        <div className="auth-form">
          <h2>{t('auth.needy_step2_title')}</h2>
          <div className="moderation-box">
            <div className="spinner"></div>
            <p>{t('auth.docs_sent')}</p>
            <p className="hint">{t('auth.docs_time')}</p>
          </div>
          <button onClick={() => setStep(3)} className="btn btn-secondary">{t('auth.simulate_approve')}</button>
        </div>
      );
    }
    return (
      <form onSubmit={handleSubmit} className="auth-form">
        <h2>{t('auth.needy_step3_title')}</h2>
        <AddressInput
          label={t('auth.home_address')}
          onChange={handleAddressChange}
          value={formData.address}
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
          <p>ℹ️ {t('auth.limit_notice')}</p>
        </div>

        <button type="submit" className="btn btn-primary">{t('auth.finish_register')}</button>
      </form>
    );
  };

  const renderTgStep = () => (
    <div className="auth-form">
      <h2>{t('auth.telegram_title')}</h2>
      <p style={{ color: '#aaa', marginBottom: 20 }}>{t('auth.telegram_desc')}</p>
      {regToken && (
        <button
          className="btn btn-primary"
          style={{ marginBottom: 12 }}
          onClick={async () => {
            try {
              const res = await fetch(`${API_URL}/auth/telegram/init-link`, {
                headers: { Authorization: `Bearer ${regToken}` },
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
        onClick={() => { setTgStep(false); setIsLogin(true); }}
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
