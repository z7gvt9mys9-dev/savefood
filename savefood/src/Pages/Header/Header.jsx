import React from "react";
import { Link } from 'react-router-dom';
import './Header.css';

const Header = () => {
    return (
        <header className="header">
            <div className="header-container">
                <div className="logo">
                    <Link to="/" style={{ textDecoration: 'none', color: 'inherit' }}>
                        <h1>SaveFood</h1>
                    </Link>
                </div>

                <nav className="nav">
                    <ul className="nav-links">
                        <li><Link to="/">Главная</Link></li>
                        <li><Link to="/about">О проекте</Link></li>
                        <li><Link to="/auth" className="btn-login">Войти</Link></li>
                    </ul>
                </nav>
            </div>
        </header>
    );
}

export default Header;