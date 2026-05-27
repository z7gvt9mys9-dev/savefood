import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { API_URL } from '../api';
import './Style/HomePage.css';

const HomePage = () => {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [openFaq, setOpenFaq] = useState(null);

  useEffect(() => {
    fetch(`${API_URL}/stats`)
      .then(res => {
        if (!res.ok) throw new Error('Failed to fetch stats');
        return res.json();
      })
      .then(data => {
        setStats(data);
        setLoading(false);
      })
      .catch(err => {
        console.error("Error fetching stats:", err);
        setError(err.message);
        setLoading(false);
      });
  }, []);

  const metrics = stats ? [
    { 
      label: 'Спасенные продукты', 
      value: `${stats.kg_food_saved.toLocaleString()} кг`, 
      sub: 'еды не было выброшено' 
    },
    { 
      label: 'Выполнено доставок', 
      value: stats.deliveries_completed.toLocaleString(), 
      sub: 'семей получили помощь' 
    },
    { 
      label: 'Наше сообщество', 
      value: stats.active_volunteers.toLocaleString(), 
      sub: 'активных волонтеров' 
    },
    { 
      label: 'Экологический след', 
      value: `~${Math.round(stats.kg_food_saved * 0.0012)} тонн`, 
      sub: 'предотвращенных выбросов CO₂' 
    },
  ] : [
    { label: 'Спасенные продукты', value: '...', sub: 'загрузка...' },
    { label: 'Выполнено доставок', value: '...', sub: 'загрузка...' },
    { label: 'Наше сообщество', value: '...', sub: 'загрузка...' },
    { label: 'Экологический след', value: '...', sub: 'загрузка...' },
  ];

  const roles = [
    {
      title: 'Магазинам и кафе',
      benefits: [
        'Публикуйте лоты за 2 минуты',
        'Авто-снятие просрочки за 24ч',
        'Получайте отчеты о списании'
      ],
      cta: 'Выдать лот'
    },
    {
      title: 'Волонтерам',
      benefits: [
        'Удобная карта и навигация',
        'Выбирайте маршруты в своем районе',
        'Помогайте в свободное время'
      ],
      cta: 'Стать волонтером'
    },
    {
      title: 'Нуждающимся',
      benefits: [
        'Прозрачный учет (раз в неделю)',
        'Быстрая модерация анкеты (24ч)',
        'Доставка прямо до двери'
      ],
      cta: 'Подать заявку'
    }
  ];

  const steps = [
    { title: t('home.how_shop'), desc: t('home.how_shop_desc') },
    { title: t('home.how_volunteer'), desc: t('home.how_volunteer_desc') },
    { title: t('home.how_needy'), desc: t('home.how_needy_desc') },
  ];

  const faqs = [
    { q: t('home.faq.free_q'), a: t('home.faq.free_a') },
    { q: t('home.faq.who_q'), a: t('home.faq.who_a') },
    { q: t('home.faq.safety_q'), a: t('home.faq.safety_a') },
    { q: t('home.faq.city_q'), a: t('home.faq.city_a') },
  ];

  return (
    <div className="home-page">
      {/* 1. Hero Block */}
      <section className="hero">
        <div className="container">
          <h1>{t('home.hero_title')}</h1>
          <p className="subtitle">{t('home.hero_sub')}</p>
          <div className="hero-ctas">
            <button className="btn btn-primary" onClick={() => navigate('/auth')}>{t('home.cta_join')}</button>
            <button className="btn btn-secondary" onClick={() => navigate('/auth')}>{t('home.cta_learn')}</button>
          </div>
        </div>
      </section>

      {/* 2. Metrics Block */}
      <section className="metrics">
        <div className="container">
          <div className="metrics-grid">
            {metrics.map((item, idx) => (
              <div key={idx} className="metric-card">
                <div className="metric-value">{item.value}</div>
                <div className="metric-label">{item.label}</div>
                <div className="metric-sub">{item.sub}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* 3. Roles Block */}
      <section className="roles">
        <div className="container">
          <h2>С чего начать?</h2>
          <div className="roles-grid">
            {roles.map((role, idx) => (
              <div key={idx} className="role-card">
                <h3>{role.title}</h3>
                <ul>
                  {role.benefits.map((b, i) => <li key={i}>{b}</li>)}
                </ul>
                <button className="btn btn-card" onClick={() => navigate('/auth')}>{role.cta}</button>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* 4. How It Works Block */}
      <section className="how-it-works">
        <div className="container">
          <h2>{t('home.how_title')}</h2>
          <div className="steps-grid">
            {steps.map((step, idx) => (
              <div key={idx} className="step-card">
                <div className="step-number">{idx + 1}</div>
                <h4>{step.title}</h4>
                <p>{step.desc}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* FAQ Block */}
      <section className="faq">
        <div className="container">
          <h2>{t('home.faq_title')}</h2>
          <div className="faq-list">
            {faqs.map((faq, idx) => {
              const isOpen = openFaq === idx;
              return (
                <div key={idx} className={`faq-item${isOpen ? ' open' : ''}`}>
                  <div className="faq-question" onClick={() => setOpenFaq(isOpen ? null : idx)}>
                    <h4>{faq.q}</h4>
                    <button className="faq-toggle" aria-label={isOpen ? 'Закрыть' : 'Открыть'}>
                      {isOpen ? '−' : '+'}
                    </button>
                  </div>
                  <div className="faq-answer">
                    <p>{faq.a}</p>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </section>
    </div>
  );
}

export default HomePage;