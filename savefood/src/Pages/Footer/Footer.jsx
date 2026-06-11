import React from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import './Footer.css';

const Footer = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useTranslation();

  const smoothScrollTo = (targetY, duration = 900) => {
    const startY = window.scrollY;
    const diff = targetY - startY;
    let start = null;
    const easeInOut = (t) => t < 0.5 ? 2 * t * t : -1 + (4 - 2 * t) * t;
    const step = (timestamp) => {
      if (!start) start = timestamp;
      const elapsed = timestamp - start;
      const progress = Math.min(elapsed / duration, 1);
      window.scrollTo(0, startY + diff * easeInOut(progress));
      if (progress < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  };

  const highlightEl = (el) => {
    el.classList.remove('highlight-active');
    void el.offsetWidth;
    el.classList.add('highlight-active');
    setTimeout(() => el.classList.remove('highlight-active'), 2000);
  };

  const handleHashLink = (path, hash) => (e) => {
    e.preventDefault();
    const scrollToHash = () => {
      if (!hash) { smoothScrollTo(0); return; }
      const el = document.getElementById(hash);
      if (el) {
        const top = el.getBoundingClientRect().top + window.scrollY - 80;
        smoothScrollTo(top);
        setTimeout(() => highlightEl(el), 600);
      }
    };

    if (location.pathname === path) {
      scrollToHash();
    } else {
      navigate(path + (hash ? '#' + hash : ''));
      setTimeout(scrollToHash, 200);
    }
  };

  return (
    <footer className="footer">
      <div className="container">
        <div className="footer-grid">
          <div className="footer-brand">
            <h2>SaveFood</h2>
            <p>{t('footer.tagline')}</p>
          </div>
          <div className="footer-links">
            <div className="link-group">
              <h4>{t('footer.platform')}</h4>
              <ul>
                <li><a href="/about#role-shop"      onClick={handleHashLink('/about', 'role-shop')}>{t('footer.for_shops')}</a></li>
                <li><a href="/about#role-volunteer"  onClick={handleHashLink('/about', 'role-volunteer')}>{t('footer.for_volunteers')}</a></li>
                <li><a href="/about#role-needy"     onClick={handleHashLink('/about', 'role-needy')}>{t('footer.for_needy')}</a></li>
              </ul>
            </div>
            <div className="link-group">
              <h4>{t('footer.company')}</h4>
              <ul>
                <li><a href="/about"          onClick={handleHashLink('/about', '')}>{t('footer.about')}</a></li>
                <li><Link to="/impact">{t('footer.impact')}</Link></li>
                <li><a href="/about#contacts" onClick={handleHashLink('/about', 'contacts')}>{t('footer.contacts')}</a></li>
                <li><a href="/about#faq"      onClick={handleHashLink('/about', 'faq')}>{t('footer.faq')}</a></li>
              </ul>
            </div>
            <div className="link-group">
              <h4>{t('footer.legal')}</h4>
              <ul>
                <li><Link to="/terms">{t('footer.terms')}</Link></li>
                <li><Link to="/privacy">{t('footer.privacy')}</Link></li>
              </ul>
            </div>
          </div>
        </div>
        <div className="footer-bottom">
          <p>&copy; 2026 SaveFood. {t('footer.rights')}.</p>
          <div className="socials">
            <a href="https://t.me/My_funny550_bot" target="_blank" rel="noreferrer">TG</a>
            <a href="/" aria-label="VK">VK</a>
          </div>
        </div>
      </div>
    </footer>
  );
};

export default Footer;
