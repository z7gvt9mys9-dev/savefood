import React from 'react';
import { Link } from 'react-router-dom';
import './Footer.css';

const Footer = () => {
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
                <li><Link to="/about#role-shop">Магазинам</Link></li>
                <li><Link to="/about#role-volunteer">Волонтерам</Link></li>
                <li><Link to="/about#role-needy">Нуждающимся</Link></li>
              </ul>
            </div>
            <div className="link-group">
              <h4>Компания</h4>
              <ul>
                <li><Link to="/about">О проекте</Link></li>
                <li><Link to="/about#contacts">Контакты</Link></li>
                <li><Link to="/about#faq">FAQ</Link></li>
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
}

export default Footer;