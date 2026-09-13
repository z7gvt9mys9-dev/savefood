import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { API_URL } from '../api';
import { useAuth } from '../context/AuthContext';
import HomePageContent from './HomePageContent';
import './HomePage.css';
import {
  LANDING_COPY,
  normalizeLandingLanguage,
} from './HomePage.locale';
const ROLE_PATHS = { shop: '/shop', volunteer: '/volunteer', needy: '/needy', admin: '/admin' };
export default function HomePage() {
  const rootRef = useRef(null);
  const previousLanguageRef = useRef(null);
  const [previewRole, setPreviewRole] = useState('recipient');
  const [selectedLot, setSelectedLot] = useState('bread');
  const [toastMessage, setToastMessage] = useState('');
  const [impactState, setImpactState] = useState({
    cities: [],
    status: 'loading',
    summary: null,
    volunteers: [],
  });
  const navigate = useNavigate();
  const { user, logout } = useAuth();
  const { i18n } = useTranslation();
  const language = normalizeLandingLanguage(i18n.resolvedLanguage || i18n.language);
  const copy = LANDING_COPY[language];
  useEffect(() => {
    const previousLanguage = previousLanguageRef.current;
    previousLanguageRef.current = language;
    if (!previousLanguage || previousLanguage === language) return undefined;
    const root = rootRef.current;
    if (!root) return undefined;
    root.classList.remove('is-language-entering');
    const frame = window.requestAnimationFrame(() => {
      void root.offsetWidth;
      root.classList.add('is-language-entering');
    });
    const timer = window.setTimeout(() => root.classList.remove('is-language-entering'), 760);
    return () => {
      window.cancelAnimationFrame(frame);
      window.clearTimeout(timer);
    };
  }, [language]);
  useEffect(() => {
    let alive = true;
    let impactTimer;
    const loadJson = async (path, fallback) => {
      const response = await fetch(`${API_URL}${path}`, { cache: 'no-store' });
      if (!response.ok) return fallback;
      return response.json();
    };
    const scheduleImpactLoad = (delay) => {
      window.clearTimeout(impactTimer);
      impactTimer = window.setTimeout(loadImpact, delay);
    };
    const loadImpact = async () => {
      try {
        const [summary, cities, volunteers] = await Promise.all([
          loadJson('/impact/summary', null),
          loadJson('/impact/cities', []),
          loadJson('/impact/volunteers', []),
        ]);
        if (!summary?.totals) throw new Error('Impact summary unavailable');
        if (!alive) return;
        setImpactState({ cities, status: 'ready', summary, volunteers });
        scheduleImpactLoad(30000);
      } catch {
        if (!alive) return;
        setImpactState({ cities: [], status: 'unavailable', summary: null, volunteers: [] });
        scheduleImpactLoad(3000);
      }
    };
    setImpactState({ cities: [], status: 'loading', summary: null, volunteers: [] });
    void loadImpact();
    return () => {
      alive = false;
      window.clearTimeout(impactTimer);
    };
  }, [language]);
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return undefined;
    const query = (selector) => root.querySelector(selector);
    const header = query('[data-header]');
    const menuButton = query('[data-menu-button]');
    const navigation = query('[data-nav]');
    let toastTimer;
    let scrollFrame;
    const showToast = (message) => {
      window.clearTimeout(toastTimer);
      setToastMessage(message);
      toastTimer = window.setTimeout(() => setToastMessage(''), 2800);
    };
    const closeMenu = () => {
      menuButton?.setAttribute('aria-expanded', 'false');
      navigation?.classList.remove('is-open');
    };
    const scrollToSection = (hash) => {
      if (!hash || hash === '#') return;
      const target = query(hash);
      if (!target) return;
      window.cancelAnimationFrame(scrollFrame);
      const start = window.scrollY;
      const headerOffset = (header?.offsetHeight || 0) + 16;
      const destination = Math.max(0, target.getBoundingClientRect().top + start - headerOffset);
      const distance = destination - start;
      const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      if (reduceMotion || Math.abs(distance) < 2) {
        window.scrollTo(0, destination);
        if (window.location.hash !== hash) window.history.pushState(null, '', hash);
        return;
      }
      const duration = Math.min(900, Math.max(620, Math.abs(distance) * 0.32));
      const startedAt = window.performance.now();
      document.documentElement.classList.add('is-programmatic-scrolling');
      const ease = (progress) => (
        progress < 0.5
          ? 4 * progress * progress * progress
          : 1 - ((-2 * progress + 2) ** 3) / 2
      );
      const step = (now) => {
        const progress = Math.min(1, (now - startedAt) / duration);
        const nextPosition = start + distance * ease(progress);
        document.documentElement.scrollTop = nextPosition;
        document.body.scrollTop = nextPosition;
        if (progress < 1) {
          scrollFrame = window.requestAnimationFrame(step);
          return;
        }
        document.documentElement.classList.remove('is-programmatic-scrolling');
        if (window.location.hash !== hash) window.history.pushState(null, '', hash);
      };
      scrollFrame = window.requestAnimationFrame(step);
    };
    const openAuth = (mode, role) => {
      const params = new URLSearchParams({ mode });
      if (role) params.set('role', role === 'recipient' ? 'needy' : role);
      closeMenu();
      navigate(`/auth?${params.toString()}`);
    };
    const onScroll = () => header?.classList.toggle('is-scrolled', window.scrollY > 20);
    const onClick = (event) => {
      const accountTrigger = event.target.closest('[data-account-action]');
      const authTrigger = event.target.closest('[data-auth-mode]');
      const lot = event.target.closest('[data-lot]');
      const demo = event.target.closest('[data-demo-action]');
      const pageAnchor = event.target.closest('.site-header a[href^="#"], .site-footer a[href^="#"]');
      const previewTab = event.target.closest('[data-preview-tab]');
      if (accountTrigger) {
        event.preventDefault();
        closeMenu();
        if (accountTrigger.dataset.accountAction === 'logout') {
          logout();
          return;
        }
        navigate(ROLE_PATHS[user?.role] || '/');
        return;
      }
      if (pageAnchor) {
        event.preventDefault();
        closeMenu();
        scrollToSection(pageAnchor.getAttribute('href'));
      }
      if (previewTab) setPreviewRole(previewTab.dataset.previewTab);
      if (authTrigger) {
        if (user?.role) {
          closeMenu();
          navigate(ROLE_PATHS[user.role] || '/');
        } else {
          openAuth(authTrigger.dataset.authMode, authTrigger.dataset.roleChoice);
        }
      }
      if (lot) setSelectedLot(lot.dataset.lot);
      if (demo) showToast(`${demo.dataset.demoAction}. ${copy.demoSuffix}`);
      const languageButton = event.target.closest('[data-language]');
      if (languageButton) {
        const nextLanguage = languageButton.dataset.language;
        if (nextLanguage === normalizeLandingLanguage(i18n.resolvedLanguage || i18n.language)) return;
        root.classList.remove('is-language-entering');
        void i18n.changeLanguage(nextLanguage);
      }
    };
    const onKeyDown = (event) => {
      const tab = event.target.closest?.('[data-preview-tab]');
      if (!tab || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const tabs = Array.from(root.querySelectorAll('[data-preview-tab]'));
      const index = tabs.indexOf(tab);
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
        : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
      setPreviewRole(tabs[next].dataset.previewTab);
      window.requestAnimationFrame(() => tabs[next].focus());
    };
    const onMenuToggle = () => {
      const open = menuButton.getAttribute('aria-expanded') === 'true';
      menuButton.setAttribute('aria-expanded', String(!open));
      navigation.classList.toggle('is-open', !open);
    };
    menuButton?.addEventListener('click', onMenuToggle);
    navigation?.addEventListener('click', closeMenu);
    root.addEventListener('click', onClick);
    root.addEventListener('keydown', onKeyDown);
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
    return () => {
      menuButton?.removeEventListener('click', onMenuToggle);
      navigation?.removeEventListener('click', closeMenu);
      root.removeEventListener('click', onClick);
      root.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('scroll', onScroll);
      window.clearTimeout(toastTimer);
      window.cancelAnimationFrame(scrollFrame);
      document.documentElement.classList.remove('is-programmatic-scrolling');
    };
  }, [copy, i18n, language, logout, navigate, user?.role]);
  return (
    <div ref={rootRef} className="ember-page" lang={language}>
      <HomePageContent
        copy={copy}
        impactState={impactState}
        isAuthenticated={Boolean(user?.role)}
        language={language}
        previewRole={previewRole}
        selectedLot={selectedLot}
        toastMessage={toastMessage}
      />
    </div>
  );
}
