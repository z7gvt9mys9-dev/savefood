import React, { useEffect } from 'react';
import { Link, useLocation } from 'react-router-dom';
import './Style/AboutPage.css';

const steps = [
  { num: '01', title: 'Магазин создаёт лот', text: 'Партнёр добавляет продукты с истекающим сроком — хлеб, овощи, готовая еда. Указывает адрес и окно выдачи.' },
  { num: '02', title: 'Волонтёр берёт маршрут', text: 'Волонтёр видит ближайшие лоты на карте, берёт маршрут и едет к магазину для сборки продуктов.' },
  { num: '03', title: 'Доставка получателю', text: 'Волонтёр привозит продукты нуждающейся семье. Передача подтверждается QR-кодом.' },
];

const stats = [
  { value: '0', label: 'кг спасённой еды' },
  { value: '0', label: 'доставок завершено' },
  { value: '0', label: 'активных волонтёров' },
  { value: '0', label: 'семей получили помощь' },
];

const AboutPage = () => {
  const { hash } = useLocation();

  useEffect(() => {
    if (!hash) return;
    const id = hash.slice(1);
    const timer = setTimeout(() => {
      const el = document.getElementById(id);
      if (!el) return;
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      el.classList.add('highlight-active');
      setTimeout(() => el.classList.remove('highlight-active'), 2200);
    }, 120);
    return () => clearTimeout(timer);
  }, [hash]);

  return (
    <div className="about-page">

      {/* Hero */}
      <section className="about-hero">
        <h1>О проекте SaveFood</h1>
        <p className="about-lead">
          Мы соединяем магазины, у которых есть лишняя еда, с людьми, которым она нужна.
          Волонтёры делают это возможным.
        </p>
      </section>

      {/* Миссия */}
      <section className="about-section">
        <div className="about-grid-2">
          <div>
            <h2>Наша миссия</h2>
            <p>
              В России ежегодно выбрасывается около <strong>17 млн тонн</strong> пригодной еды.
              SaveFood — платформа, которая сокращает эти потери, направляя продукты тем,
              кто в них нуждается, прежде чем они окажутся в мусоре.
            </p>
            <p>
              Мы работаем в связке магазин → волонтёр → получатель. Каждая доставка —
              это реальная помощь и реальное сокращение пищевых отходов.
            </p>
          </div>
          <div className="about-values">
            <div className="value-item"><b>Без отходов</b></div>
            <div className="value-item"><b>Адресная помощь</b></div>
            <div className="value-item"><b>Конфиденциально</b></div>
            <div className="value-item"><b>Быстро и просто</b></div>
          </div>
        </div>
      </section>

      {/* Как работает */}
      <section className="about-section about-section--dark">
        <h2>Как это работает</h2>
        <div className="steps-row">
          {steps.map((s, i) => (
            <div key={i} className="step-card">
              <span className="step-num">{s.num}</span>
              <h3>{s.title}</h3>
              <p>{s.text}</p>
            </div>
          ))}
        </div>
      </section>

      {/* Статистика */}
      <section className="about-section">
        <h2>Платформа в цифрах</h2>
        <div className="about-stats">
          {stats.map((s, i) => (
            <div key={i} className="about-stat">
              <span className="about-stat-value">{s.value}</span>
              <span className="about-stat-label">{s.label}</span>
            </div>
          ))}
        </div>
      </section>

      {/* Кто участвует */}
      <section className="about-section about-section--dark">
        <h2>Кто может участвовать</h2>
        <div className="roles-grid">
          <div id="role-shop" className="role-card">
            <h3>Магазины и кафе</h3>
            <p>Регистрируйтесь как партнёр, добавляйте лоты с продуктами и получайте уведомления о доставке.</p>
            <Link to="/auth" className="role-cta">Стать партнёром</Link>
          </div>
          <div id="role-volunteer" className="role-card">
            <h3>Волонтёры</h3>
            <p>Принимайте маршруты, собирайте продукты и доставляйте их тем, кто нуждается в помощи.</p>
            <Link to="/auth" className="role-cta">Стать волонтёром</Link>
          </div>
          <div id="role-needy" className="role-card">
            <h3>Получатели</h3>
            <p>Многодетные семьи и малоимущие граждане могут получать продукты после прохождения верификации.</p>
            <Link to="/auth" className="role-cta">Подать заявку</Link>
          </div>
        </div>
      </section>

      {/* Конфиденциальность */}
      <section className="about-section">
        <h2>Защита данных</h2>
        <p className="about-privacy">
          Документы, подтверждающие статус получателя, удаляются сразу после модерации.
          Мы не передаём личные данные третьим лицам. Местоположение используется
          исключительно для маршрутизации волонтёра и нигде не хранится.
        </p>
      </section>

      {/* FAQ */}
      <section id="faq" className="about-section">
        <h2>Часто задаваемые вопросы</h2>
        <div className="faq-list">
          <div className="faq-item">
            <h4>Кто может стать волонтером?</h4>
            <p>Любой желающий от 18 лет с возможностью передвигаться по городу. Регистрация занимает пару минут.</p>
          </div>
          <div className="faq-item">
            <h4>Как магазин добавляет продукты?</h4>
            <p>После регистрации магазин создает лоты с описанием, фото и сроком хранения. Волонтер получает уведомление и забирает заказ.</p>
          </div>
          <div className="faq-item">
            <h4>Нужно ли подтверждать статус нуждающегося?</h4>
            <p>Да, при первой заявке потребуется загрузить подтверждающий документ. После проверки администратором он сразу удаляется.</p>
          </div>
          <div className="faq-item">
            <h4>Платформа бесплатна?</h4>
            <p>Полностью бесплатна для всех участников. Мы некоммерческий проект.</p>
          </div>
        </div>
      </section>

      {/* Контакты */}
      <section id="contacts" className="about-section about-section--dark">
        <h2>Контакты</h2>
        <div className="contacts-grid">
          <div className="contact-item">
            <span className="contact-label">Telegram</span>
            <a href="https://t.me/My_funny550_bot" target="_blank" rel="noreferrer">@My_funny550_bot</a>
          </div>
          <div className="contact-item">
            <span className="contact-label">Email</span>
            <a href="mailto:igel2020i@gmail.com">igel2020i@gmail.com</a>
          </div>
          <div className="contact-item">
            <span className="contact-label">Для магазинов</span>
            <p>Отправьте заявку через форму регистрации, мы свяжемся в течение суток.</p>
          </div>
        </div>
      </section>

      {/* CTA */}
      <section className="about-cta-section">
        <h2>Присоединяйтесь</h2>
        <p>Каждый может сделать что-то полезное. Начните сейчас.</p>
        <Link to="/auth" className="btn btn-primary about-cta-btn">Зарегистрироваться</Link>
      </section>

    </div>
  );
};

export default AboutPage;
