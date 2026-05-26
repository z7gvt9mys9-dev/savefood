import React, { useState, useEffect } from 'react';
import { useAuth } from '../../context/AuthContext';
import './Admin.css';

const AdminPanel = () => {
  const { user } = useAuth();
  const [activeTab, setActiveTab] = useState('moderation');
  const [pendingNeedy, setPendingNeedy] = useState([]);
  const [stats, setStats] = useState({});
  const [activeRoutes, setActiveRoutes] = useState([]);

  const authHeader = { Authorization: `Bearer ${user?.token}` };

  const fetchData = async () => {
    try {
      const [needyRes, statsRes, routesRes] = await Promise.all([
        fetch('http://localhost:8000/admin/needy?status=pending', { headers: authHeader }),
        fetch('http://localhost:8000/admin/stats', { headers: authHeader }),
        fetch('http://localhost:8000/admin/routes', { headers: authHeader }),
      ]);
      if (needyRes.ok) setPendingNeedy(await needyRes.json());
      if (statsRes.ok) setStats(await statsRes.json());
      if (routesRes.ok) setActiveRoutes(await routesRes.json());
    } catch {}
  };

  useEffect(() => {
    fetchData();
  }, []);

  const handleApprove = async (id) => {
    try {
      await fetch(`http://localhost:8000/needy/${id}/moderation?status=approved`, {
        method: 'PATCH',
        headers: authHeader,
      });
      fetchData();
    } catch {}
  };

  const handleReject = async (id) => {
    try {
      await fetch(`http://localhost:8000/needy/${id}/moderation?status=rejected`, {
        method: 'PATCH',
        headers: authHeader,
      });
      fetchData();
    } catch {}
  };

  const renderModeration = () => (
    <div className="admin-tab">
      <h2>Очередь модерации (24ч)</h2>
      <table className="admin-table">
        <thead>
          <tr>
            <th>Пользователь</th>
            <th>Контакт</th>
            <th>Документ</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {pendingNeedy.length === 0 ? (
            <tr><td colSpan="4" style={{ textAlign: 'center', padding: '20px' }}>Очередь пуста</td></tr>
          ) : pendingNeedy.map(item => (
            <tr key={item.id}>
              <td>{item.name}</td>
              <td>{item.contact || '—'}</td>
              <td>{item.document ? <a href={`http://localhost:8000/needy_uploads/${item.document.split('/').pop()}`} target="_blank" rel="noreferrer">Просмотреть</a> : '—'}</td>
              <td>
                <button className="btn-small btn-success" onClick={() => handleApprove(item.id)}>Одобрить</button>
                <button className="btn-small btn-danger" onClick={() => handleReject(item.id)}>Отклонить</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );

  const renderDispatcher = () => (
    <div className="admin-tab">
      <h2>Диспетчерская (Мониторинг)</h2>
      <div className="incident-list">
        <h3>Активные маршруты</h3>
        {activeRoutes.length === 0 ? (
          <p className="empty-msg">Нет активных маршрутов</p>
        ) : activeRoutes.map(r => (
          <div key={r.id} className="incident-card">
            <p><strong>Волонтер:</strong> {r.volunteer_name || `ID ${r.volunteer_id}`}</p>
            <p><strong>Маршрут №{r.id}</strong></p>
            <p><strong>Начат:</strong> {new Date(r.started_at).toLocaleString()}</p>
          </div>
        ))}
      </div>
    </div>
  );

  const renderAnalytics = () => (
    <div className="admin-tab">
      <h2>Аналитика эффективности</h2>
      <div className="analytics-grid">
        <div className="analytic-card">
          <h4>Еды спасено (кг)</h4>
          <p className="big-value">{stats.kg_food_saved ?? '—'}</p>
        </div>
        <div className="analytic-card">
          <h4>Доставок завершено</h4>
          <p className="big-value">{stats.deliveries_completed ?? '—'}</p>
        </div>
        <div className="analytic-card">
          <h4>Активных волонтеров (30д)</h4>
          <p className="big-value">{stats.active_volunteers ?? '—'}</p>
        </div>
        <div className="analytic-card">
          <h4>Среднее время доставки</h4>
          <p className="big-value">{stats.avg_delivery_minutes != null ? `${stats.avg_delivery_minutes} мин` : '—'}</p>
        </div>
        <div className="analytic-card">
          <h4>Процент просрочки лотов</h4>
          <p className="big-value yellow-text">{stats.percent_expired_lots != null ? `${stats.percent_expired_lots}%` : '—'}</p>
        </div>
      </div>
    </div>
  );

  return (
    <div className="dashboard-container admin-container">
      <aside className="sidebar">
        <h2>SaveFood Admin</h2>
        <nav>
          <button className={activeTab === 'moderation' ? 'active' : ''} onClick={() => setActiveTab('moderation')}>Модерация</button>
          <button className={activeTab === 'dispatcher' ? 'active' : ''} onClick={() => setActiveTab('dispatcher')}>Диспетчерская</button>
          <button className={activeTab === 'analytics' ? 'active' : ''} onClick={() => setActiveTab('analytics')}>Аналитика</button>
        </nav>
      </aside>

      <main className="main-content">
        {activeTab === 'moderation' && renderModeration()}
        {activeTab === 'dispatcher' && renderDispatcher()}
        {activeTab === 'analytics' && renderAnalytics()}
      </main>
    </div>
  );
};

export default AdminPanel;
