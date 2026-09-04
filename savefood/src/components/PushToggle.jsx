import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { API_URL, authFetch } from '../api';
import MonoIcon from './MonoIcon';
const urlBase64ToUint8Array = (base64String) => {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = window.atob(base64);
  return Uint8Array.from([...raw].map(c => c.charCodeAt(0)));
};
const PushToggle = () => {
  const { user } = useAuth();
  const { t } = useTranslation();
  const authHeader = {};
  const [publicKey, setPublicKey] = useState(null);
  const [subscribed, setSubscribed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const supported = typeof window !== 'undefined'
    && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
  useEffect(() => {
    if (!supported) return;
    fetch(`${API_URL}/push/public_key`)
      .then(r => r.ok ? r.json() : null)
      .then(data => { if (data?.key) setPublicKey(data.key); })
      .catch(() => {});
    navigator.serviceWorker.getRegistration()
      .then(reg => reg ? reg.pushManager.getSubscription() : null)
      .then(sub => setSubscribed(!!sub))
      .catch(() => {});
  }, []);
  if (!supported || !publicKey) return null;
  const getRegistration = async () => {
    const reg = await navigator.serviceWorker.getRegistration();
    if (reg) return reg;
    return navigator.serviceWorker.register('/sw.js');
  };
  const enable = async () => {
    setBusy(true);
    setError('');
    try {
      if (Notification.permission === 'denied') {
        setError('denied');
        return;
      }
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') {
        setError('denied');
        return;
      }
      const reg = await getRegistration();
      const sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(publicKey),
      });
      const res = await authFetch(`${API_URL}/push/subscribe`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify(sub.toJSON()),
      });
      if (res.ok) {
        setSubscribed(true);
      } else {
        await sub.unsubscribe().catch(() => {});
        setError('server');
      }
    } catch {
      setError('error');
    } finally {
      setBusy(false);
    }
  };
  const disable = async () => {
    setBusy(true);
    setError('');
    try {
      const reg = await navigator.serviceWorker.getRegistration();
      const sub = reg ? await reg.pushManager.getSubscription() : null;
      if (sub) {
        await authFetch(`${API_URL}/push/unsubscribe`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...authHeader },
          body: JSON.stringify({ endpoint: sub.endpoint }),
        }).catch(() => {});
        await sub.unsubscribe().catch(() => {});
      }
    } catch {
    } finally {
      setSubscribed(false);
      setBusy(false);
    }
  };
  return (
    <div className="info-section" style={{ marginTop: 12 }}>
      <h3>
        <MonoIcon name="bell" /> {t('push.title')}
        {subscribed && (
          <span style={{
            marginLeft: 8, fontSize: '0.72rem', verticalAlign: 'middle',
            color: '#4CAF50', border: '1px solid #4CAF5055', borderRadius: 10,
            padding: '2px 8px', background: '#4CAF5015', fontWeight: 600,
          }}>
            ✓ {t('push.enabled_badge')}
          </span>
        )}
      </h3>
      <p style={{ opacity: 0.75, fontSize: '0.85rem' }}>
        {subscribed ? t('push.enabled_hint') : t('push.disabled_hint')}
      </p>
      <button
        className={`btn-small ${subscribed ? 'btn-danger' : 'btn-success'}`}
        disabled={busy}
        onClick={subscribed ? disable : enable}
      >
        {busy ? t('common.loading') : subscribed ? t('push.disable') : t('push.enable')}
      </button>
      {error && (
        <p style={{ color: '#FF9800', fontSize: '0.8rem', margin: '8px 0 0' }}>
          {error === 'denied' && t('push.error_denied')}
          {error === 'server' && t('push.error_server')}
          {error === 'error' && t('push.error_generic')}
        </p>
      )}
    </div>
  );
};
export default PushToggle;
