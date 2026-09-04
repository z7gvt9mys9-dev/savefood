import React, { useEffect } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import './Style/AboutPage.css';
const AboutPage = () => {
  const { hash } = useLocation();
  const { t } = useTranslation();
  const steps = [
    { num: t('about.step1_num'), title: t('about.step1_title'), text: t('about.step1_text') },
    { num: t('about.step2_num'), title: t('about.step2_title'), text: t('about.step2_text') },
    { num: t('about.step3_num'), title: t('about.step3_title'), text: t('about.step3_text') },
  ];
  const stats = [
    { value: '0', label: t('about.stat1_label') },
    { value: '0', label: t('about.stat2_label') },
    { value: '0', label: t('about.stat3_label') },
    { value: '0', label: t('about.stat4_label') },
  ];
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
      <section className="about-hero">
        <h1>{t('about.title')}</h1>
        <p className="about-lead">{t('about.lead')}</p>
      </section>
      <section className="about-section">
        <div className="about-grid-2">
          <div>
            <h2>{t('about.mission_title')}</h2>
            <p>{t('about.mission_text1')}</p>
            <p>{t('about.mission_text2')}</p>
          </div>
          <div className="about-values">
            <div className="value-item"><b>{t('about.val_zero_waste')}</b></div>
            <div className="value-item"><b>{t('about.val_targeted')}</b></div>
            <div className="value-item"><b>{t('about.val_private')}</b></div>
            <div className="value-item"><b>{t('about.val_simple')}</b></div>
          </div>
        </div>
      </section>
      <section className="about-section about-section--dark">
        <h2>{t('about.how_title')}</h2>
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
      <section className="about-section">
        <h2>{t('about.stats_platform')}</h2>
        <div className="about-stats">
          {stats.map((s, i) => (
            <div key={i} className="about-stat">
              <span className="about-stat-value">{s.value}</span>
              <span className="about-stat-label">{s.label}</span>
            </div>
          ))}
        </div>
      </section>
      <section className="about-section about-section--dark">
        <h2>{t('about.who_title')}</h2>
        <div className="roles-grid">
          <div id="role-shop" className="role-card">
            <h3>{t('about.shop_title')}</h3>
            <p>{t('about.shop_desc')}</p>
            <Link to="/auth" className="role-cta">{t('about.shop_cta')}</Link>
          </div>
          <div id="role-volunteer" className="role-card">
            <h3>{t('about.volunteer_title')}</h3>
            <p>{t('about.volunteer_desc')}</p>
            <Link to="/auth" className="role-cta">{t('about.volunteer_cta')}</Link>
          </div>
          <div id="role-needy" className="role-card">
            <h3>{t('about.needy_title')}</h3>
            <p>{t('about.needy_desc')}</p>
            <Link to="/auth" className="role-cta">{t('about.needy_cta')}</Link>
          </div>
        </div>
      </section>
      <section className="about-section">
        <h2>{t('about.privacy_title')}</h2>
        <p className="about-privacy">{t('about.privacy_text')}</p>
      </section>
      <section id="faq" className="about-section">
        <h2>{t('about.faq_title')}</h2>
        <div className="faq-list">
          <div className="faq-item">
            <h4>{t('about.faq_q1')}</h4>
            <p>{t('about.faq_a1')}</p>
          </div>
          <div className="faq-item">
            <h4>{t('about.faq_q2')}</h4>
            <p>{t('about.faq_a2')}</p>
          </div>
          <div className="faq-item">
            <h4>{t('about.faq_q3')}</h4>
            <p>{t('about.faq_a3')}</p>
          </div>
          <div className="faq-item">
            <h4>{t('about.faq_q4')}</h4>
            <p>{t('about.faq_a4')}</p>
          </div>
        </div>
      </section>
      <section id="contacts" className="about-section about-section--dark">
        <h2>{t('about.contacts_title')}</h2>
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
            <span className="contact-label">{t('about.contacts_for_shops')}</span>
            <p>{t('about.contacts_for_shops_desc')}</p>
          </div>
        </div>
      </section>
      <section className="about-cta-section">
        <h2>{t('about.join_title')}</h2>
        <p>{t('about.join_text')}</p>
        <Link to="/auth" className="btn btn-primary about-cta-btn">{t('about.join_btn')}</Link>
      </section>
    </div>
  );
};
export default AboutPage;
