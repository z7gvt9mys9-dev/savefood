import React, { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { API_URL } from '../api';
import './Style/ImpactPage.css';

const POLL_MS = 20000;

// Public city dashboard: live counters of rescued food / CO2 / meals,
// the inter-city leaderboard and the anonymous delivery photo feed.
// No auth — this page is the platform's PR surface (media, city halls, sponsors).
const ImpactPage = () => {
  const { t } = useTranslation();
  const [summary, setSummary] = useState(null);
  const [cities, setCities] = useState([]);
  const [teams, setTeams] = useState([]);
  const [feed, setFeed] = useState([]);
  const [error, setError] = useState(false);
  const pollRef = useRef(null);

  const fetchAll = async () => {
    try {
      const [sRes, cRes, tRes, fRes] = await Promise.all([
        fetch(`${API_URL}/impact/summary`),
        fetch(`${API_URL}/impact/cities`),
        fetch(`${API_URL}/impact/teams`),
        fetch(`${API_URL}/impact/feed?limit=12`),
      ]);
      if (sRes.ok) setSummary(await sRes.json());
      if (cRes.ok) setCities(await cRes.json());
      if (tRes.ok) setTeams(await tRes.json());
      if (fRes.ok) setFeed(await fRes.json());
      setError(!sRes.ok);
    } catch {
      setError(true);
    }
  };

  useEffect(() => {
    fetchAll();
    pollRef.current = setInterval(fetchAll, POLL_MS);
    return () => clearInterval(pollRef.current);
  }, []);

  const totals = summary?.totals;
  const counters = totals ? [
    { value: Math.round(totals.kg).toLocaleString(), label: t('impact.kg_saved'), accent: true },
    { value: Math.round(totals.co2_kg).toLocaleString(), label: t('impact.co2_prevented') },
    { value: totals.meals.toLocaleString(), label: t('impact.meals') },
    { value: (summary.deliveries_completed ?? 0).toLocaleString(), label: t('impact.deliveries') },
    { value: (summary.active_volunteers ?? 0).toLocaleString(), label: t('impact.volunteers') },
    { value: (summary.partner_shops ?? 0).toLocaleString(), label: t('impact.shops') },
  ] : [];

  return (
    <div className="impact-page">
      <section className="impact-hero">
        <h1>{t('impact.title')}</h1>
        <p>{t('impact.subtitle')}</p>
        <span className="impact-live">● {t('impact.live')}</span>
      </section>

      {error && !summary && <p className="impact-error">{t('common.connection_error')}</p>}

      <section className="impact-counters">
        {counters.map((c, i) => (
          <div key={i} className={`impact-counter ${c.accent ? 'accent' : ''}`}>
            <span className="impact-value">{c.value}</span>
            <span className="impact-label">{c.label}</span>
          </div>
        ))}
      </section>

      {summary?.by_month?.length > 0 && (
        <section className="impact-section">
          <h2>{t('impact.monthly_title')}</h2>
          <ResponsiveContainer width="100%" height={260}>
            <BarChart data={summary.by_month} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
              <XAxis dataKey="month" tick={{ fill: '#aaa', fontSize: 12 }} />
              <YAxis tick={{ fill: '#aaa', fontSize: 12 }} />
              <Tooltip contentStyle={{ background: '#1e1e2e', border: '1px solid #333', color: '#fff' }} />
              <Bar dataKey="kg" name={t('impact.kg_saved')} fill="#4CAF50" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </section>
      )}

      {cities.length > 0 && (
        <section className="impact-section">
          <h2>{t('impact.cities_title')}</h2>
          <div className="impact-cities">
            {cities.map((c, i) => (
              <div key={c.city} className="impact-city-row">
                <span className="impact-city-rank">{['🥇', '🥈', '🥉'][i] || i + 1}</span>
                <span className="impact-city-name">{c.city}</span>
                <div className="impact-city-bar">
                  <div
                    className="impact-city-fill"
                    style={{ width: `${Math.max(4, Math.round((c.kg / (cities[0].kg || 1)) * 100))}%` }}
                  />
                </div>
                <span className="impact-city-kg">{Math.round(c.kg)} {t('impact.kg')}</span>
              </div>
            ))}
          </div>
        </section>
      )}

      {teams.length > 0 && (
        <section className="impact-section">
          <h2>{t('impact.teams_title')}</h2>
          <p className="impact-feed-hint">{t('impact.teams_hint')}</p>
          <div className="impact-cities">
            {teams.map((tm, i) => (
              <div key={tm.id} className="impact-city-row">
                <span className="impact-city-rank">{['🥇', '🥈', '🥉'][i] || i + 1}</span>
                <span className="impact-city-name">🏢 {tm.name}</span>
                <div className="impact-city-bar">
                  <div
                    className="impact-city-fill"
                    style={{ width: `${Math.max(4, Math.round((tm.deliveries / (teams[0].deliveries || 1)) * 100))}%`, background: 'linear-gradient(90deg, #2196F3, #64B5F6)' }}
                  />
                </div>
                <span className="impact-city-kg" style={{ color: '#64B5F6' }}>
                  {tm.deliveries} · {Math.round(tm.kg)} {t('impact.kg')}
                </span>
              </div>
            ))}
          </div>
        </section>
      )}

      {feed.length > 0 && (
        <section className="impact-section">
          <h2>{t('impact.feed_title')}</h2>
          <p className="impact-feed-hint">{t('impact.feed_hint')}</p>
          <div className="impact-feed">
            {feed.map((item, i) => (
              <figure key={i} className="impact-card">
                <img
                  src={`${API_URL}${item.photo}`}
                  alt={t('impact.feed_alt')}
                  loading="lazy"
                  onError={(e) => { e.target.closest('figure').style.display = 'none'; }}
                />
                <figcaption>
                  {item.category && <span className="category-badge">{item.category}</span>}
                  <span className="impact-card-meta">
                    {item.city ? `${item.city} · ` : ''}
                    {item.date ? new Date(item.date).toLocaleDateString() : ''}
                  </span>
                </figcaption>
              </figure>
            ))}
          </div>
        </section>
      )}

      {summary?.methodology && (
        <p className="impact-methodology">{t('impact.methodology')}: {summary.methodology}</p>
      )}
    </div>
  );
};

export default ImpactPage;
