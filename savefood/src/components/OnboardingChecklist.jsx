import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { API_URL } from '../api';

// Interactive onboarding: a short checklist of real first steps, computed from
// actual account state (not a fake tour). Disappears once everything is done
// or after an explicit dismiss (kept in localStorage per user).
//
// `items`: [{ id, label, done }] from the parent dashboard's loaded data.
// `withTelegram`: prepends a "connect Telegram" item; its done-state is
// fetched here from /auth/links so parents don't have to care.
const OnboardingChecklist = ({ storageKey, items = [], withTelegram = true }) => {
  const { user } = useAuth();
  const { t } = useTranslation();
  const lsKey = `onboarding_dismissed_${storageKey}_${user?.relatedId ?? ''}`;

  const [dismissed, setDismissed] = useState(() => localStorage.getItem(lsKey) === '1');
  const [tgLinked, setTgLinked] = useState(null);

  useEffect(() => {
    if (!withTelegram || dismissed) return;
    fetch(`${API_URL}/auth/links`, { headers: { Authorization: `Bearer ${user?.token}` } })
      .then(r => r.ok ? r.json() : null)
      .then(data => { if (data) setTgLinked(!!data.telegram); })
      .catch(() => {});
  }, [withTelegram, dismissed]);

  const allItems = [
    ...(withTelegram ? [{ id: 'telegram', label: t('onboarding.connect_telegram'), done: !!tgLinked }] : []),
    ...items,
  ];
  const doneCount = allItems.filter(i => i.done).length;

  if (dismissed || allItems.length === 0 || doneCount === allItems.length) return null;

  const dismiss = () => {
    localStorage.setItem(lsKey, '1');
    setDismissed(true);
  };

  return (
    <div style={{ background: '#FF980012', border: '1px solid #FF980044', borderRadius: 12, padding: '12px 14px', marginBottom: 14 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 8 }}>
        <strong style={{ color: '#FFB74D' }}>🚀 {t('onboarding.title')}</strong>
        <span style={{ fontSize: '0.78rem', color: '#aaa' }}>{doneCount}/{allItems.length}</span>
      </div>
      <ul style={{ listStyle: 'none', padding: 0, margin: '8px 0' }}>
        {allItems.map(item => (
          <li key={item.id} style={{ padding: '3px 0', fontSize: '0.88rem', opacity: item.done ? 0.55 : 1 }}>
            {item.done ? '✅' : '⬜'} <span style={{ textDecoration: item.done ? 'line-through' : 'none' }}>{item.label}</span>
          </li>
        ))}
      </ul>
      <button className="btn-small" onClick={dismiss} style={{ opacity: 0.8 }}>
        {t('onboarding.dismiss')}
      </button>
    </div>
  );
};

export default OnboardingChecklist;
