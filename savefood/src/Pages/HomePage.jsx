import { useEffect, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { API_URL } from '../api';
import landingDocument from './HomePage.markup.html?raw';
import './HomePage.css';
import {
  LANDING_COPY,
  LANDING_LOTS,
  localizeLandingMarkup,
  normalizeLandingLanguage,
} from './HomePage.locale';

const bodyMatch = landingDocument.match(/<body>([\s\S]*)<\/body>/i);
const landingMarkup = bodyMatch ? bodyMatch[1] : landingDocument;

const animateLanguageWords = (root) => {
  const document = root.ownerDocument;
  const showText = document.defaultView.NodeFilter.SHOW_TEXT;
  const walker = document.createTreeWalker(root, showText);
  const textNodes = [];
  const wordIndexes = new Map();

  while (walker.nextNode()) textNodes.push(walker.currentNode);

  textNodes.forEach((textNode) => {
    if (!textNode.textContent.trim()) return;
    const parent = textNode.parentElement;
    if (!parent || parent.closest('script, style, svg, [hidden], [aria-hidden="true"], .skip-link, .sr-only')) return;

    const group = parent.closest('h1, h2, h3, p, a, button, li, strong, summary, label, [role="tabpanel"]') || parent;
    let wordIndex = wordIndexes.get(group) || 0;
    const words = document.createElement('span');
    words.className = 'language-words';

    textNode.textContent.split(/(\s+)/).forEach((part) => {
      if (!part || /^\s+$/.test(part)) {
        words.append(part);
        return;
      }

      const word = document.createElement('span');
      word.className = 'language-word';
      word.style.setProperty('--language-word-order', String(Math.min(wordIndex, 12)));
      word.textContent = part;
      words.append(word);
      wordIndex += 1;
    });

    wordIndexes.set(group, wordIndex);
    textNode.replaceWith(words);
  });
};

/**
 * The public landing page keeps its production markup and styles next to this
 * component so changes in design references cannot affect the live product.
 */
export default function HomePage() {
  const rootRef = useRef(null);
  const previousLanguageRef = useRef(null);
  const navigate = useNavigate();
  const { i18n } = useTranslation();
  const language = normalizeLandingLanguage(i18n.resolvedLanguage || i18n.language);
  const copy = LANDING_COPY[language];
  const markup = useMemo(() => localizeLandingMarkup(landingMarkup, language), [language]);

  useEffect(() => {
    const previousLanguage = previousLanguageRef.current;
    previousLanguageRef.current = language;
    if (!previousLanguage || previousLanguage === language) return undefined;

    const root = rootRef.current;
    if (!root) return undefined;
    root.classList.remove('is-language-entering');
    animateLanguageWords(root);
    void root.offsetWidth;
    root.classList.add('is-language-entering');
    const timer = window.setTimeout(() => root.classList.remove('is-language-entering'), 760);
    return () => window.clearTimeout(timer);
  }, [language]);

  useEffect(() => {
    const root = rootRef.current;
    if (!root) return undefined;

    const query = (selector) => root.querySelector(selector);
    const queryAll = (selector) => Array.from(root.querySelectorAll(selector));
    const header = query('[data-header]');
    const menuButton = query('[data-menu-button]');
    const navigation = query('[data-nav]');
    const toast = query('[data-toast]');
    let toastTimer;
    let scrollFrame;
    let impactTimer;
    let alive = true;

    const headerActions = query('.header-actions');
    if (headerActions && !query('[data-language]')) {
      headerActions.insertAdjacentHTML('beforeend', `
        <div class="ember-language-switcher" aria-label="${language === 'en' ? 'Choose language' : 'Выбор языка'}">
          <button type="button" data-language="ru">RU</button>
          <button type="button" data-language="en">EN</button>
        </div>
      `);
    }
    const setLanguageButton = (language) => {
      queryAll('[data-language]').forEach((button) => {
        button.classList.toggle('is-active', button.dataset.language === language);
      });
    };
    setLanguageButton(language);

    const showToast = (message) => {
      window.clearTimeout(toastTimer);
      toast.textContent = message;
      toast.classList.add('is-visible');
      toastTimer = window.setTimeout(() => toast.classList.remove('is-visible'), 2800);
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
    const setPreview = (role, focus = false) => {
      queryAll('[data-preview-tab]').forEach((tab) => {
        const selected = tab.dataset.previewTab === role;
        tab.setAttribute('aria-selected', String(selected));
        tab.tabIndex = selected ? 0 : -1;
        if (selected && focus) tab.focus();
      });
      queryAll('[data-preview-panel]').forEach((panel) => {
        const selected = panel.dataset.previewPanel === role;
        panel.hidden = !selected;
        panel.classList.toggle('is-entering', selected);
        if (selected) window.setTimeout(() => panel.classList.remove('is-entering'), 260);
      });
    };
    const setLot = (lot) => {
      const details = LANDING_LOTS[language][lot];
      if (!details) return;
      queryAll('[data-lot]').forEach((control) => {
        const selected = control.dataset.lot === lot;
        control.classList.toggle('is-active', selected);
        if (control.classList.contains('mini-lot')) control.setAttribute('aria-pressed', String(selected));
      });
      query('[data-lot-title]').textContent = details[0];
      query('[data-lot-time]').textContent = details[1];
    };
    const openAuth = (mode, role) => {
      const params = new URLSearchParams({ mode });
      if (role) params.set('role', role === 'recipient' ? 'needy' : role);
      closeMenu();
      navigate(`/auth?${params.toString()}`);
    };
    const onScroll = () => header?.classList.toggle('is-scrolled', window.scrollY > 20);
    const onClick = (event) => {
      const authTrigger = event.target.closest('[data-auth-mode]');
      const lot = event.target.closest('[data-lot]');
      const demo = event.target.closest('[data-demo-action]');
      const pageAnchor = event.target.closest('.site-header a[href^="#"], .site-footer a[href^="#"]');
      const previewTab = event.target.closest('[data-preview-tab]');
      if (pageAnchor) {
        event.preventDefault();
        closeMenu();
        scrollToSection(pageAnchor.getAttribute('href'));
      }
      if (previewTab) setPreview(previewTab.dataset.previewTab);
      if (authTrigger) openAuth(authTrigger.dataset.authMode, authTrigger.dataset.roleChoice);
      if (lot) setLot(lot.dataset.lot);
      if (demo) showToast(`${demo.dataset.demoAction}. ${copy.demoSuffix}`);
      const language = event.target.closest('[data-language]');
      if (language) {
        const nextLanguage = language.dataset.language;
        if (nextLanguage === normalizeLandingLanguage(i18n.resolvedLanguage || i18n.language)) return;
        setLanguageButton(nextLanguage);
        root.classList.remove('is-language-entering');
        void i18n.changeLanguage(nextLanguage);
      }
    };
    const onKeyDown = (event) => {
      const tab = event.target.closest?.('[data-preview-tab]');
      if (!tab || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const tabs = queryAll('[data-preview-tab]');
      const index = tabs.indexOf(tab);
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
        : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
      setPreview(tabs[next].dataset.previewTab, true);
    };
    const onMenuToggle = () => {
      const open = menuButton.getAttribute('aria-expanded') === 'true';
      menuButton.setAttribute('aria-expanded', String(!open));
      navigation.classList.toggle('is-open', !open);
    };
    const format = (value, options) => new Intl.NumberFormat(copy.locale, options).format(Number(value) || 0);
    const clearImpactDemo = () => {
      const impact = query('#impact');
      if (!impact) return;
      const proofIntro = query('.proof-intro span:last-child');
      if (proofIntro) proofIntro.innerHTML = copy.impactLoading;
      queryAll('.proof-item strong').forEach((node) => { node.textContent = '—'; });
      const main = impact.querySelector('.impact-main__header');
      main?.querySelector('small')?.replaceChildren(copy.rescuedFood);
      main?.querySelector('strong')?.replaceChildren('—');
      const trend = main?.querySelector('.trend');
      if (trend) trend.hidden = true;
      const metrics = impact.querySelectorAll('.impact-metric');
      metrics[0]?.querySelector('strong')?.replaceChildren('—');
      metrics[1]?.querySelector('strong')?.replaceChildren('—');
      const cities = impact.querySelector('.city-ranking ol');
      if (cities) cities.replaceChildren();
    };
    const buildNode = (tag, text, className) => {
      const node = document.createElement(tag);
      if (className) node.className = className;
      if (text !== undefined) node.textContent = text;
      return node;
    };
    const renderImpact = (summary, cities, volunteers) => {
      const impact = query('#impact');
      if (!impact || !summary?.totals) return;
      const totals = summary.totals;
      const kg = Number(totals.kg) || 0;
      const co2Kg = Number(totals.co2_kg) || 0;
      const meals = Number(totals.meals) || 0;
      const deliveries = Number(summary.deliveries_completed) || 0;
      const activeVolunteers = Number(summary.active_volunteers) || 0;
      const co2Display = co2Kg >= 1000
        ? { value: format(co2Kg / 1000, { maximumFractionDigits: 1 }), unit: copy.tons }
        : { value: format(co2Kg, { maximumFractionDigits: 1 }), unit: copy.kg };
      const proofIntro = query('.proof-intro span:last-child');
      if (proofIntro) proofIntro.innerHTML = copy.impactReady;
      const proof = queryAll('.proof-item strong');
      [format(kg), format(meals), `${co2Display.value} ${co2Display.unit}`, format(deliveries)]
        .forEach((value, index) => { if (proof[index]) proof[index].textContent = value; });

      const main = impact.querySelector('.impact-main__header');
      const value = main?.querySelector('strong');
      if (value) {
        value.replaceChildren(document.createTextNode(`${format(kg)} `));
        value.append(buildNode('span', copy.kg));
      }
      const months = Array.isArray(summary.by_month) ? summary.by_month.slice(-6) : [];
      const trend = main?.querySelector('.trend');
      if (trend) {
        const previous = Number(months.at(-2)?.kg) || 0;
        const current = Number(months.at(-1)?.kg) || 0;
        if (previous > 0) {
          trend.hidden = false;
          trend.textContent = `${current >= previous ? '↑' : '↓'} ${format(Math.abs((current - previous) / previous) * 100, { maximumFractionDigits: 1 })}%`;
        }
      }
      const bars = impact.querySelector('.bars');
      if (bars) {
        const max = Math.max(...months.map((entry) => Number(entry.kg) || 0), 1);
        bars.replaceChildren(...months.map((entry, index) => {
          const bar = buildNode('span', undefined, index === months.length - 1 ? 'is-current' : '');
          bar.style.setProperty('--bar', `${Math.max(4, ((Number(entry.kg) || 0) / max) * 100)}%`);
          const label = buildNode('i', new Intl.DateTimeFormat(copy.locale, { month: 'short' })
            .format(new Date(`${entry.month}-01T00:00:00`)).replace('.', ''));
          bar.append(label);
          return bar;
        }));
      }

      const metrics = impact.querySelectorAll('.impact-metric');
      const co2Value = metrics[0]?.querySelector('strong');
      if (co2Value) {
        co2Value.replaceChildren(document.createTextNode(`${co2Display.value} `));
        co2Value.append(buildNode('span', co2Display.unit));
      }
      const volunteerValue = metrics[1]?.querySelector('strong');
      if (volunteerValue) {
        volunteerValue.replaceChildren(document.createTextNode(`${format(activeVolunteers)} `));
        volunteerValue.append(buildNode('span', copy.people));
      }
      const avatars = impact.querySelector('.avatar-stack');
      if (avatars) {
        const visible = Array.isArray(volunteers) ? volunteers.slice(0, 3) : [];
        avatars.replaceChildren(...visible.map((person) => buildNode('span', String(person.name || 'В').slice(0, 2).toUpperCase())));
        avatars.append(buildNode('span', `+${Math.max(0, activeVolunteers - visible.length)}`));
      }
      const list = impact.querySelector('.city-ranking ol');
      if (list) {
        const top = Number(cities?.[0]?.kg) || 1;
        list.replaceChildren(...(Array.isArray(cities) ? cities.slice(0, 3) : []).map((city, index) => {
          const item = buildNode('li');
          item.append(buildNode('span', String(index + 1), 'rank'));
          item.append(buildNode('strong', city.city || copy.unnamedCity));
          const bar = buildNode('div', undefined, 'city-bar');
          const fill = buildNode('span');
          fill.style.setProperty('--city', `${Math.max(0, Math.min(100, (Number(city.kg) || 0) / top * 100))}%`);
          bar.append(fill);
          item.append(bar);
          item.append(buildNode('em', `${format(city.kg)} ${copy.kg}`));
          return item;
        }));
      }
    };
    const scheduleImpactLoad = (delay) => {
      window.clearTimeout(impactTimer);
      impactTimer = window.setTimeout(loadImpact, delay);
    };
    const loadJson = async (path, fallback) => {
      const response = await fetch(`${API_URL}${path}`, { cache: 'no-store' });
      if (!response.ok) return fallback;
      return response.json();
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
        renderImpact(summary, cities, volunteers);
        scheduleImpactLoad(30000);
      } catch {
        if (!alive) return;
        const proofIntro = query('.proof-intro span:last-child');
        if (proofIntro) proofIntro.innerHTML = copy.impactUnavailable;
        scheduleImpactLoad(3000);
      }
    };
    clearImpactDemo();
    void loadImpact();

    menuButton?.addEventListener('click', onMenuToggle);
    navigation?.addEventListener('click', closeMenu);
    root.addEventListener('click', onClick);
    root.addEventListener('keydown', onKeyDown);
    window.addEventListener('scroll', onScroll, { passive: true });
    queryAll('[data-current-year]').forEach((node) => { node.textContent = String(new Date().getFullYear()); });
    onScroll();

    return () => {
      menuButton?.removeEventListener('click', onMenuToggle);
      navigation?.removeEventListener('click', closeMenu);
      root.removeEventListener('click', onClick);
      root.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('scroll', onScroll);
      window.clearTimeout(toastTimer);
      window.clearTimeout(impactTimer);
      window.cancelAnimationFrame(scrollFrame);
      document.documentElement.classList.remove('is-programmatic-scrolling');
      alive = false;
    };
  }, [copy, i18n, language, navigate]);

  return <div ref={rootRef} className="ember-page" lang={language} dangerouslySetInnerHTML={{ __html: markup }} />;
}
