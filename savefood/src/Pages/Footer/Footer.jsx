import React from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import './Footer.css';

const Footer = () => {
  const navigate = useNavigate();
  const location = useLocation();

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
            <p>Превращаем излишки продуктов в реальную помощь.</p>
          </div>
          <div className="footer-links">
            <div className="link-group">
              <h4>Платформа</h4>
              <ul>
                <li><a href="/about#role-shop"    onClick={handleHashLink('/about', 'role-shop')}>Магазинам</a></li>
                <li><a href="/about#role-volunteer" onClick={handleHashLink('/about', 'role-volunteer')}>Волонтерам</a></li>
                <li><a href="/about#role-needy"   onClick={handleHashLink('/about', 'role-needy')}>Нуждающимся</a></li>
              </ul>
            </div>
            <div className="link-group">
              <h4>Компания</h4>
              <ul>
                <li><a href="/about"          onClick={handleHashLink('/about', '')}>О проекте</a></li>
                <li><a href="/about#contacts" onClick={handleHashLink('/about', 'contacts')}>Контакты</a></li>
                <li><a href="/about#faq"      onClick={handleHashLink('/about', 'faq')}>FAQ</a></li>
              </ul>
            </div>
          </div>
        </div>
        <div className="footer-bottom">
          <p>&copy; 2026 SaveFood. All rights reserved.</p>
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
