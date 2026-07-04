import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { API_URL } from '../api';

// Profile block: link / unlink Telegram, Google and Yandex.
// Telegram uses the existing bot deep-link (init-link); Google/Yandex go
// through the server-side OAuth flow (/auth/oauth/{p}/start?mode=link), the
// callback redirects back to `dashboardPath` with #linked=<provider>.
const PROVIDER_META = [
  { id: 'telegram', icon: '✈️' },
  { id: 'google', icon: 'G' },
  { id: 'yandex', icon: 'Я' },
];

const AccountLinks = ({ dashboardPath = '/' }) => {
  const { user } = useAuth();
  const { t } = useTranslation();
  const authHeader = { Authorization: `Bearer ${user?.token}` };

  const [links, setLinks] = useState(null);
  const [providers, setProviders] = useState(null);
  const [tgLink, setTgLink] = useState(null);
  const [busy, setBusy] = useState('');
  const [justLinked, setJustLinked] = useState('');

  const loadLinks = () => {
    fetch(`${API_URL}/auth/links`, { headers: authHeader })
      .then(r => r.ok ? r.json() : null)
      .then(data => { if (data) setLinks(data); })
      .catch(() => {});
  };

  useEffect(() => {
    loadLinks();
    fetch(`${API_URL}/auth/oauth/providers`)
      .then(r => r.ok ? r.json() : null)
      .then(data => { if (data) setProviders(data); })
      .catch(() => {});
    // OAuth callback lands back here with #linked=<provider>
    const hash = new URLSearchParams(window.location.hash.slice(1));
    const linked = hash.get('linked');
    if (linked) {
      setJustLinked(linked);
      window.history.replaceState(null, '', window.location.pathname);
    }
  }, []);

  // While the Telegram deep-link is on screen and the account is still not
  // linked, poll the status so the row flips to «привязан» by itself once the
  // user presses Start in the bot (no manual «Обновить» needed).
  useEffect(() => {
    if (!tgLink || links?.telegram) return;
    const timer = setInterval(loadLinks, 3000);
    const stop = setTimeout(() => clearInterval(timer), 120000);
    return () => { clearInterval(timer); clearTimeout(stop); };
  }, [tgLink, links?.telegram]);

  const handleLink = async (provider) => {
    setBusy(provider);
    try {
      if (provider === 'telegram') {
        const res = await fetch(`${API_URL}/auth/telegram/init-link`, { headers: authHeader });
        if (!res.ok) { alert(t('common.error')); return; }
        setTgLink(await res.json());
        return;
      }
      const res = await fetch(
        `${API_URL}/auth/oauth/${provider}/start?mode=link&next=${encodeURIComponent(dashboardPath)}`,
        { headers: authHeader },
      );
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        alert(err.detail || t('common.error'));
        return;
      }
      const data = await res.json();
      window.location.href = data.url;
    } catch {
      alert(t('common.connection_error'));
    } finally {
      setBusy('');
    }
  };

  const handleUnlink = async (provider) => {
    if (!window.confirm(t('links.confirm_unlink'))) return;
    setBusy(provider);
    try {
      const res = await fetch(`${API_URL}/auth/links/${provider}/unlink`, {
        method: 'POST',
        headers: authHeader,
      });
      if (res.ok) { setTgLink(null); loadLinks(); }
    } catch {} finally { setBusy(''); }
  };

  if (!user) return null;

  const rowStyle = {
    display: 'flex', alignItems: 'center', gap: 10,
    padding: '8px 0', borderBottom: '1px solid #ffffff14',
  };

  return (
    <div className="tg-connect-section">
      <h3>{t('links.title')}</h3>
      {justLinked && (
        <p style={{ color: '#4CAF50', fontSize: '0.85em' }}>
          ✓ {t('links.linked_success', { provider: t(`links.${justLinked}`) })}
        </p>
      )}
      {PROVIDER_META.map(({ id, icon }) => {
        if (providers && !providers[id]) return null; // not configured on server
        const isLinked = links?.[id];
        return (
          <div key={id} style={rowStyle}>
            <span style={{ width: 22, textAlign: 'center', fontWeight: 700 }}>{icon}</span>
            <span style={{ flex: 1 }}>{t(`links.${id}`)}</span>
            <span style={{ fontSize: '0.8em', color: isLinked ? '#4CAF50' : '#888' }}>
              {links == null ? '…' : isLinked ? t('links.linked') : t('links.not_linked')}
            </span>
            {isLinked ? (
              <button className="btn-small btn-danger" disabled={busy === id} onClick={() => handleUnlink(id)}>
                {t('links.unlink')}
              </button>
            ) : (
              <button className="btn-small" disabled={busy === id} onClick={() => handleLink(id)}>
                {busy === id ? '…' : t('links.connect')}
              </button>
            )}
          </div>
        );
      })}
      {tgLink && !links?.telegram && (
        <div style={{ marginTop: 10 }}>
          <a href={tgLink.link} target="_blank" rel="noreferrer" className="btn btn-primary tg-btn"
             onClick={() => setTimeout(loadLinks, 4000)}>
            {t('links.open_telegram', { name: tgLink.bot_name })}
          </a>
          <button className="btn-small" style={{ marginLeft: 8 }} onClick={loadLinks}>
            {t('links.refresh_status')}
          </button>
        </div>
      )}
    </div>
  );
};

export default AccountLinks;
