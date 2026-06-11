import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { API_URL } from '../api';

const urlBase64ToUint8Array = (base64String) => {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = window.atob(base64);
  return Uint8Array.from([...raw].map(c => c.charCodeAt(0)));
};

// Browser/PWA push subscription toggle. Renders nothing when the backend has
// no VAPID keys (GET /push/public_key → 503) or the browser lacks Push API —
// Telegram remains the primary channel in that case.
const PushToggle = () => {
  const { user } = useAuth();
  const { t } = useTranslation();
  const authHeader = { Authorization: `Bearer ${user?.token}` };

  const [publicKey, setPublicKey] = useState(null);
  const [subscribed, setSubscribed] = useState(false);
  const [busy, setBusy] = useState(false);

  const supported = typeof window !== 'undefined'
    && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;

  useEffect(() => {
    if (!supported) return;
    fetch(`${API_URL}/push/public_key`)
      .then(r => r.ok ? r.json() : null)
      .then(data => { if (data?.key) setPublicKey(data.key); })
      .catch(() => {});
    navigator.serviceWorker.ready
      .then(reg => reg.pushManager.getSubscription())
      .then(sub => setSubscribed(!!sub))
      .catch(() => {});
  }, []);

  if (!supported || !publicKey) return null;

  const enable = async () => {
    setBusy(true);
    try {
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') return;
      const reg = await navigator.serviceWorker.ready;
      const sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(publicKey),
      });
      const res = await fetch(`${API_URL}/push/subscribe`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify(sub.toJSON()),
      });
      if (res.ok) setSubscribed(true);
      else await sub.unsubscribe();
    } catch {} finally { setBusy(false); }
  };

  const disable = async () => {
    setBusy(true);
    try {
      const reg = await navigator.serviceWorker.ready;
      const sub = await reg.pushManager.getSubscription();
      if (sub) {
        await fetch(`${API_URL}/push/unsubscribe`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...authHeader },
          body: JSON.stringify({ endpoint: sub.endpoint }),
        }).catch(() => {});
        await sub.unsubscribe();
      }
      setSubscribed(false);
    } catch {} finally { setBusy(false); }
  };

  return (
    <div className="info-section" style={{ marginTop: 12 }}>
      <h3>🔔 {t('push.title')}</h3>
      <p style={{ opacity: 0.75, fontSize: '0.85rem' }}>
        {subscribed ? t('push.enabled_hint') : t('push.disabled_hint')}
      </p>
      <button
        className={`btn-small ${subscribed ? '' : 'btn-success'}`}
        disabled={busy}
        onClick={subscribed ? disable : enable}
      >
        {busy ? t('common.loading') : subscribed ? t('push.disable') : t('push.enable')}
      </button>
    </div>
  );
};

export default PushToggle;
