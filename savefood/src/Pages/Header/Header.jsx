import React, { useState } from "react";
import { Link, NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { useTranslation } from 'react-i18next';
import './Header.css';

const ROLE_PATHS = { shop: '/shop', volunteer: '/volunteer', needy: '/needy', admin: '/admin' };
const LANGS = [{ code: 'ru', label: 'RU' }, { code: 'kk', label: 'KZ' }, { code: 'en', label: 'EN' }];

const Header = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
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

  return (
    <header className="header">
      <div className="header-container">
        <Link to="/" className="logo-link" onClick={close}>
          <span className="logo-text">SaveFood</span>
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
