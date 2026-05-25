import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import './Style/HomePage.css';

const HomePage = () => {
  const navigate = useNavigate();
  const [openFaq, setOpenFaq] = useState(null);
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    fetch('http://localhost:8000/stats')
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

  const toggleFaq = (index) => {
    setOpenFaq(openFaq === index ? null : index);
  };

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
    { title: 'Магазин отдает', desc: 'Кафе или супермаркет выкладывает продукты, у которых заканчивается срок.' },
    { title: 'Волонтер везет', desc: 'Наш алгоритм строит оптимальный маршрут, волонтер забирает заказ.' },
    { title: 'Семья получает', desc: 'Волонтер передает продукты лично в руки и сканирует безопасный QR-код.' }
  ];

  const faqs = [
    {
      q: 'Что если волонтер не приедет?',
      a: 'Наша служба поддержки переназначит маршрут в течение 30 минут.'
    },
    {
      q: 'Вся ли еда безопасна?',
      a: 'Да, лоты автоматически удаляются системой за 24 часа до истечения срока годности.'
    },
    {
      q: 'Как распределяется еда, если её мало?',
      a: 'У нас действует честная система приоритетов, учитывающая размер семьи и срочность.'
    }
  ];

  return (
    <div className="home-page">
      {/* 1. Hero Block */}
      <section className="hero">
        <div className="container">
          <h1>Спасаем еду. Помогаем людям.</h1>
          <p className="subtitle">Платформа, которая соединяет магазины, волонтеров и тех, кто нуждается в продуктах питания.</p>
          <div className="hero-ctas">
            <button className="btn btn-primary" onClick={() => navigate('/auth')}>Стать партнером</button>
            <button className="btn btn-secondary" onClick={() => navigate('/auth')}>Хочу помочь</button>
            <button className="btn btn-outline" onClick={() => navigate('/auth')}>Получить помощь</button>
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
          <h2>Как это работает</h2>
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

      {/* 6. FAQ Block */}
      <section className="faq">
        <div className="container">
          <h2>Ответы на острые вопросы</h2>
          <div className="faq-list">
            {faqs.map((faq, idx) => (
              <div key={idx} className={`faq-item ${openFaq === idx ? 'open' : ''}`}>
                <div className="faq-question" onClick={() => toggleFaq(idx)}>
                  {faq.q}
                  <span className="faq-icon">{openFaq === idx ? '−' : '+'}</span>
                </div>
                {openFaq === idx && <div className="faq-answer">{faq.a}</div>}
              </div>
            ))}
          </div>
        </div>
      </section>
    </div>
  );
}

export default HomePage;