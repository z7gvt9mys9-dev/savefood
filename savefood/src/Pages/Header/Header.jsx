import React, { useState } from "react";
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { useTranslation } from 'react-i18next';
import './Header.css';

const ROLE_PATHS = { shop: '/shop', volunteer: '/volunteer', needy: '/needy', admin: '/admin' };
const LANGS = [{ code: 'ru', label: 'RU' }, { code: 'en', label: 'EN' }];

const LogoSymbol = () => (
  <svg className="logo-symbol" viewBox="0 0 48 48" aria-hidden="true">
    <path className="logo-symbol__base" d="M24 3.5c11.8 0 20.5 8.7 20.5 20.2 0 11.9-8.8 20.8-20.5 20.8S3.5 35.6 3.5 23.7C3.5 12.2 12.2 3.5 24 3.5Z" />
    <path className="logo-symbol__ring" d="M24 7.5c9.3 0 16.5 6.8 16.5 16.3 0 9.7-7.2 16.7-16.5 16.7S7.5 33.5 7.5 23.8C7.5 14.3 14.7 7.5 24 7.5Z" />
    <path className="logo-symbol__leaf" d="M24.6 16.4c.2-5.6 3.7-9 9.4-9.4-.3 5.5-3.8 9-9.4 9.4Z" />
    <path className="logo-symbol__stem" d="M24.5 17.2c1.3-3 3.7-5.4 7.1-7.2" />
    <path className="logo-symbol__bowl" d="M11.8 22.1h24.4C35.6 30.5 31 36 24 36s-11.6-5.5-12.2-13.9Z" />
    <path className="logo-symbol__route" d="M17.2 25.8c1.8 2.5 4 3.8 6.8 3.8s5-1.3 6.8-3.8" />
  </svg>
);

const Header = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const { t, i18n } = useTranslation();
  const [open, setOpen] = useState(false);

  const close = () => setOpen(false);

  const handleLogout = () => {
    logout();
    navigate('/');
    close();
  };

  const switchLang = (code) => {
    i18n.changeLanguage(code);
    close();
  };

  if (location.pathname === '/') return null;

  return (
    <header className="header">
      <div className="header-container">
        <Link to="/" className="logo-link" onClick={close}>
          <LogoSymbol />
          <span className="logo-text">Save<span>Food</span></span>
        </Link>

        <button className={`burger ${open ? 'active' : ''}`} onClick={() => setOpen(o => !o)} aria-label={t('nav.menu')}>
          <span /><span /><span />
        </button>

        <nav className={`nav ${open ? 'open' : ''}`}>
          <ul className="nav-links">
            <li><NavLink to="/" end onClick={close}>{t('nav.home')}</NavLink></li>
            <li><NavLink to="/about" onClick={close}>{t('nav.about')}</NavLink></li>
            {user ? (
              <>
                <li>
                  <NavLink to={ROLE_PATHS[user.role] || '/'} className="nav-profile" onClick={close}>
                    {t(`nav.roles.${user.role}`) || user.role}
                  </NavLink>
                </li>
                <li>
                  <button className="btn-logout" onClick={handleLogout}>{t('nav.logout')}</button>
                </li>
              </>
            ) : (
              <li><Link to="/auth" className="btn-login" onClick={close}>{t('nav.login')}</Link></li>
            )}
            <li className="lang-switcher">
              {LANGS.map(l => (
                <button
                  key={l.code}
                  className={`lang-btn ${i18n.language === l.code ? 'active' : ''}`}
                  onClick={() => switchLang(l.code)}
                >
                  {l.label}
                </button>
              ))}
            </li>
          </ul>
        </nav>

        {open && <div className="nav-backdrop" onClick={close} />}
      </div>
    </header>
  );
};

export default Header;
