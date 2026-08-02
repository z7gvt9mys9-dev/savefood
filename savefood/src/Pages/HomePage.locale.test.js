import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { LANDING_LOTS, localizeLandingMarkup } from './HomePage.locale';

const landingDocument = readFileSync(resolve(process.cwd(), 'src/Pages/HomePage.markup.html'), 'utf8');
const bodyMatch = landingDocument.match(/<body>([\s\S]*)<\/body>/i);
const markup = bodyMatch ? bodyMatch[1] : landingDocument;

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
    expect(localizeLandingMarkup(markup, 'ru')).toBe(markup);
  });

  it('translates all visible copy and accessible labels into English', () => {
    const english = localizeLandingMarkup(markup, 'en');
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
    template.innerHTML = markup;
    expect(template.content.querySelector('.selection-bar .selection-statuses')).not.toBeNull();
    expect(template.content.querySelector('.product-preview > .preview-signals')).toBeNull();
  });

  it('routes every landing CTA to a real product action', () => {
    const template = document.createElement('template');
    template.innerHTML = markup;
    expect(template.content.querySelector('[data-open-dialog]')).toBeNull();
    expect(template.content.querySelector('[data-auth-mode="login"]')).not.toBeNull();
    expect(template.content.querySelector('[data-auth-mode="register"]')).not.toBeNull();
    expect(template.content.querySelector('a[href="/terms"]')).not.toBeNull();
    expect(template.content.querySelector('a[href="/privacy"]')).not.toBeNull();
    expect(template.content.querySelector('a[href="/impact"]')).not.toBeNull();
  });
});
