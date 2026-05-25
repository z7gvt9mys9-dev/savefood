import React from 'react';
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
                <li><a href="/">Магазинам</a></li>
                <li><a href="/">Волонтерам</a></li>
                <li><a href="/">Нуждающимся</a></li>
              </ul>
            </div>
            <div className="link-group">
              <h4>Компания</h4>
              <ul>
                <li><a href="/">О проекте</a></li>
                <li><a href="/">Контакты</a></li>
                <li><a href="/">FAQ</a></li>
              </ul>
            </div>
          </div>
        </div>
        <div className="footer-bottom">
          <p>&copy; 2026 SaveFood. All rights reserved.</p>
          <div className="socials">
            <a href="/">TG</a>
            <a href="/">VK</a>
          </div>
        </div>
      </div>
    </footer>
  );
}

export default Footer;