import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import {
  API_URL,
  authFetch,
  browserPushBelongsTo,
  browserPushOwnedBy,
  forgetBrowserPushOwnership,
  getAccessToken,
  rememberBrowserPushOwnership,
  samePushAccount,
  setBrowserPushDisplayEnabled,
} from '../api';
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
  const [publicKey, setPublicKey] = useState(null);
  const [subscribed, setSubscribed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [reconciling, setReconciling] = useState(true);
  const [error, setError] = useState('');
  const pushOperation = useRef(0);
  const supported = typeof window !== 'undefined'
    && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
  useEffect(() => {
    if (!supported) return;
    fetch(`${API_URL}/push/public_key`)
      .then(r => r.ok ? r.json() : null)
      .then(data => { if (data?.key) setPublicKey(data.key); })
      .catch(() => {});
  }, []);
  useEffect(() => {
    if (!supported || !user?.token) {
      setReconciling(false);
      return undefined;
    }
    const operation = ++pushOperation.current;
    let cancelled = false;
    setReconciling(true);
    const isCurrent = () => !cancelled && pushOperation.current === operation
      && samePushAccount(user.token, getAccessToken());
    (async () => {
      try {
        const reg = await navigator.serviceWorker.getRegistration();
        const sub = reg ? await reg.pushManager.getSubscription() : null;
        if (!isCurrent()) return;
        if (!sub) {
          forgetBrowserPushOwnership();
          await setBrowserPushDisplayEnabled(false);
          if (isCurrent()) setSubscribed(false);
          return;
        }
        if (browserPushBelongsTo(sub.endpoint, user.token)) {
          await setBrowserPushDisplayEnabled(true);
          if (isCurrent()) setSubscribed(true);
          return;
        }
        await setBrowserPushDisplayEnabled(false);
        if (!isCurrent()) return;
        await sub.unsubscribe().catch(() => false);
        if (!isCurrent()) return;
        forgetBrowserPushOwnership();
        setSubscribed(false);
      } catch {
        if (isCurrent()) {
          await setBrowserPushDisplayEnabled(false);
          if (isCurrent()) setSubscribed(false);
        }
      } finally {
        if (isCurrent()) setReconciling(false);
      }
    })();
    return () => {
      cancelled = true;
      if (pushOperation.current === operation) pushOperation.current += 1;
    };
  }, [supported, user?.token]);
  if (!supported || !publicKey) return null;
  const getRegistration = async () => {
    const reg = await navigator.serviceWorker.getRegistration();
    if (reg) return reg;
    return navigator.serviceWorker.register('/sw.js');
  };
  const enable = async () => {
    const operation = ++pushOperation.current;
    const enablingAccessToken = user.token;
    const isCurrent = () => pushOperation.current === operation
      && samePushAccount(enablingAccessToken, getAccessToken());
    const cleanUpStaleEnable = async (sub, serverSubscribed) => {
      const currentAccessToken = getAccessToken();
      const activeAccountOwnsBrowserPush = browserPushOwnedBy(currentAccessToken);
      if (samePushAccount(enablingAccessToken, currentAccessToken)
          && browserPushBelongsTo(sub.endpoint, currentAccessToken)) return;
      if (serverSubscribed) {
        try {
          await fetch(`${API_URL}/push/unsubscribe`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              Authorization: `Bearer ${enablingAccessToken}`,
            },
            body: JSON.stringify({ endpoint: sub.endpoint }),
            keepalive: true,
          });
        } catch {
        }
      }
      if (activeAccountOwnsBrowserPush) return;
      await setBrowserPushDisplayEnabled(false);
      await sub.unsubscribe().catch(() => false);
      if (browserPushBelongsTo(sub.endpoint, enablingAccessToken)) {
        forgetBrowserPushOwnership();
      }
    };
    setBusy(true);
    setError('');
    try {
      if (Notification.permission === 'denied') {
        setError('denied');
        return;
      }
      const permission = await Notification.requestPermission();
      if (!isCurrent()) return;
      if (permission !== 'granted') {
        setError('denied');
        return;
      }
      const reg = await getRegistration();
      let sub = await reg.pushManager.getSubscription();
      if (!isCurrent()) return;
      if (sub && !browserPushBelongsTo(sub.endpoint, user.token)) {
        await setBrowserPushDisplayEnabled(false);
        const removed = await sub.unsubscribe();
        if (!isCurrent()) return;
        forgetBrowserPushOwnership();
        if (!removed) throw new Error('stale push subscription could not be removed');
        sub = null;
      }
      if (!sub) {
        sub = await reg.pushManager.subscribe({
          userVisibleOnly: true,
          applicationServerKey: urlBase64ToUint8Array(publicKey),
        });
        if (!isCurrent()) {
          await cleanUpStaleEnable(sub, false);
          return;
        }
      }
      const res = await authFetch(`${API_URL}/push/subscribe`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(sub.toJSON()),
      });
      if (!isCurrent()) {
        await cleanUpStaleEnable(sub, res.ok);
        return;
      }
      if (res.ok) {
        rememberBrowserPushOwnership(sub.endpoint, user.token);
        await setBrowserPushDisplayEnabled(true);
        if (isCurrent()) setSubscribed(true);
      } else {
        await sub.unsubscribe().catch(() => {});
        forgetBrowserPushOwnership();
        await setBrowserPushDisplayEnabled(false);
        setError('server');
      }
    } catch {
      setError('error');
    } finally {
      setBusy(false);
    }
  };
  const disable = async () => {
    const operation = ++pushOperation.current;
    const isCurrent = () => pushOperation.current === operation
      && samePushAccount(user.token, getAccessToken());
    setBusy(true);
    setError('');
    try {
      const reg = await navigator.serviceWorker.getRegistration();
      const sub = reg ? await reg.pushManager.getSubscription() : null;
      if (!isCurrent()) return;
      if (sub) {
        await authFetch(`${API_URL}/push/unsubscribe`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ endpoint: sub.endpoint }),
        }).catch(() => {});
        if (!isCurrent()) return;
        await sub.unsubscribe().catch(() => {});
      }
    } catch {
    } finally {
      if (isCurrent()) {
        forgetBrowserPushOwnership();
        await setBrowserPushDisplayEnabled(false);
        if (isCurrent()) setSubscribed(false);
      }
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
        disabled={busy || reconciling}
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
