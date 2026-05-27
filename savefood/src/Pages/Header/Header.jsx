import React, { useState } from "react";
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import './Header.css';

const ROLE_LABELS = { shop: 'Магазин', volunteer: 'Волонтёр', needy: 'Получатель', admin: 'Админ' };
const ROLE_PATHS  = { shop: '/shop',  volunteer: '/volunteer',  needy: '/needy',  admin: '/admin' };

const Header = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);

  const close = () => setOpen(false);

  const handleLogout = () => {
    logout();
    navigate('/');
    close();
  };

  return (
    <header className="header">
      <div className="header-container">
        <Link to="/" className="logo-link" onClick={close}>
          <span className="logo-text">SaveFood</span>
        </Link>

        <button className={`burger ${open ? 'active' : ''}`} onClick={() => setOpen(o => !o)} aria-label="Меню">
          <span /><span /><span />
        </button>

        <nav className={`nav ${open ? 'open' : ''}`}>
          <ul className="nav-links">
            <li><Link to="/" onClick={close}>Главная</Link></li>
            <li><Link to="/about" onClick={close}>О проекте</Link></li>
            {user ? (
              <>
                <li>
                  <Link to={ROLE_PATHS[user.role] || '/'} className="nav-profile" onClick={close}>
                    {ROLE_LABELS[user.role] || user.role}
                  </Link>
                </li>
                <li>
                  <button className="btn-logout" onClick={handleLogout}>Выйти</button>
                </li>
              </>
            ) : (
              <li><Link to="/auth" className="btn-login" onClick={close}>Войти</Link></li>
            )}
          </ul>
        </nav>

        {open && <div className="nav-backdrop" onClick={close} />}
      </div>
    </header>
  );
};

export default Header;
