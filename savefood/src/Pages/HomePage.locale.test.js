import { describe, expect, it } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import HomePageContent from './HomePageContent';
import {
  LANDING_COPY,
  LANDING_LOTS,
} from './HomePage.locale';
const renderLanding = ({ isAuthenticated = false, language = 'ru' } = {}) => (
  renderToStaticMarkup(createElement(HomePageContent, {
    copy: LANDING_COPY[language],
    isAuthenticated,
    language,
  }))
);
const collectCyrillic = (html) => {
  const template = document.createElement('template');
  template.innerHTML = html;
  const values = [];
  template.content.querySelectorAll('*').forEach((element) => {
    Array.from(element.childNodes).forEach((node) => {
      const value = node.nodeType === 3 ? (node.nodeValue || '').trim() : '';
      if (/[А-Яа-яЁё]/.test(value)) values.push(value);
    });
    ['aria-label', 'placeholder', 'data-demo-action'].forEach((attribute) => {
      const value = element.getAttribute(attribute) || '';
      if (/[А-Яа-яЁё]/.test(value)) values.push(value);
    });
  });
  return values;
};
describe('production landing localization', () => {
  it('keeps the source markup for Russian', () => {
    const markup = renderLanding();
    expect(markup).toContain('Спасаем еду.');
    expect(markup).toContain('Доставляем заботу.');
  });
  it('translates all visible copy and accessible labels into English', () => {
    const english = renderLanding({ language: 'en' });
    expect(english).toContain('Saving food.');
    expect(english).toContain('delivery confirmed');
    expect(collectCyrillic(english)).toEqual([]);
  });
  it('provides localized interactive lot content', () => {
    expect(LANDING_LOTS.en.produce).toEqual([
      'Fruit and vegetables',
      'Pick up today, 18:30–20:00',
    ]);
  });
  it('keeps delivery statuses inside the selected-lot bar', () => {
    const template = document.createElement('template');
    template.innerHTML = renderLanding();
    expect(template.content.querySelector('.selection-bar .selection-statuses')).not.toBeNull();
    expect(template.content.querySelector('.product-preview > .preview-signals')).toBeNull();
  });
  it('routes every landing CTA to a real product action', () => {
    const template = document.createElement('template');
    template.innerHTML = renderLanding();
    expect(template.content.querySelector('.skip-link')).toBeNull();
    expect(template.content.querySelector('[data-open-dialog]')).toBeNull();
    expect(template.content.querySelector('[data-auth-mode="login"]')).not.toBeNull();
    expect(template.content.querySelector('[data-auth-mode="register"]')).not.toBeNull();
    expect(template.content.querySelector('a[href="/terms"]')).not.toBeNull();
    expect(template.content.querySelector('a[href="/privacy"]')).not.toBeNull();
    expect(template.content.querySelector('a[href="/impact"]')).not.toBeNull();
  });
  it('replaces public header actions for an authenticated volunteer', () => {
    const template = document.createElement('template');
    template.innerHTML = renderLanding({ isAuthenticated: true });
    const header = template.content.querySelector('.header-actions');
    const avatar = header.querySelector('[data-account-action="dashboard"]');
    expect(header.querySelector('[data-auth-mode]')).toBeNull();
    expect(header.classList.contains('header-actions--authenticated')).toBe(true);
    expect(avatar?.classList.contains('landing-account-avatar')).toBe(true);
    expect(avatar?.getAttribute('aria-label')).toBe('Открыть профиль');
    expect(avatar?.textContent).toBe('');
    expect(avatar?.querySelector('svg')).not.toBeNull();
    expect(header.lastElementChild).toBe(avatar);
    expect(header.querySelector('[data-account-action="logout"]')?.textContent).toBe('Выйти');
    expect(template.content.querySelector('[data-auth-mode="register"]')).not.toBeNull();
  });
});
