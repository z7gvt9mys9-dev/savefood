import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { API_URL, authFetch } from '../api';
import './OnboardingChecklist.css';
const OnboardingChecklist = ({ storageKey, items = [], withTelegram = true }) => {
  const { user } = useAuth();
  const { t } = useTranslation();
  const lsKey = `onboarding_dismissed_${storageKey}_${user?.relatedId ?? ''}`;
  const [dismissed, setDismissed] = useState(() => localStorage.getItem(lsKey) === '1');
  const [tgLinked, setTgLinked] = useState(null);
  useEffect(() => {
    if (!withTelegram || dismissed) return;
    authFetch(`${API_URL}/auth/links`)
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
    <section className="onboarding-card" aria-label={t('onboarding.title')}>
      <div className="onboarding-head">
        <span className="onboarding-mark" aria-hidden="true">
          <svg viewBox="0 0 20 20" fill="none">
            <path d="M4 3.5v13M5 4.5h9l-2 3 2 3H5" />
          </svg>
        </span>
        <strong>{t('onboarding.title')}</strong>
        <span className="onboarding-progress">{String(doneCount).padStart(2, '0')} / {String(allItems.length).padStart(2, '0')}</span>
      </div>
      <ul className="onboarding-list">
        {allItems.map(item => (
          <li key={item.id} className={item.done ? 'is-done' : ''}>
            <span
              className={`onboarding-checkbox${item.done ? ' is-checked' : ''}`}
              role="checkbox"
              aria-checked={item.done}
              aria-readonly="true"
            >
              {item.done && (
                <svg viewBox="0 0 18 18" fill="none" aria-hidden="true">
                  <path d="m4.5 9.3 2.8 2.8 6.2-6.2" />
                </svg>
              )}
            </span>
            <span className="onboarding-label">{item.label}</span>
          </li>
        ))}
      </ul>
      <button className="onboarding-dismiss" onClick={dismiss}>
        {t('onboarding.dismiss')}
      </button>
    </section>
  );
};
export default OnboardingChecklist;
