import React, { useState, useEffect } from 'react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell, PieChart, Pie, Legend } from 'recharts';
import { useAuth } from '../../context/AuthContext';
import { API_URL } from '../../api';
import './Admin.css';

const AdminPanel = () => {
  const { user } = useAuth();
  const [activeTab, setActiveTab] = useState('moderation');
  const [pendingNeedy, setPendingNeedy] = useState([]);
  const [stats, setStats] = useState({});
  const [activeRoutes, setActiveRoutes] = useState([]);
  const [users, setUsers] = useState([]);
  const [auditLog, setAuditLog] = useState([]);

  const authHeader = { Authorization: `Bearer ${user?.token}` };

  const fetchData = async () => {
    try {
      const [needyRes, statsRes, routesRes] = await Promise.all([
        fetch(`${API_URL}/admin/needy?status=pending`, { headers: authHeader }),
        fetch(`${API_URL}/admin/stats`, { headers: authHeader }),
        fetch(`${API_URL}/admin/routes`, { headers: authHeader }),
      ]);
      if (needyRes.ok) setPendingNeedy(await needyRes.json());
      if (statsRes.ok) setStats(await statsRes.json());
      if (routesRes.ok) setActiveRoutes(await routesRes.json());
    } catch {}
  };

  const fetchUsers = async () => {
    try {
      const res = await fetch(`${API_URL}/admin/users`, { headers: authHeader });
      if (res.ok) setUsers(await res.json());
    } catch {}
  };

  useEffect(() => {
    fetchData();
  }, []);

  const fetchAuditLog = async () => {
    try {
      const res = await fetch(`${API_URL}/admin/audit?limit=50&offset=0`, { headers: authHeader });
      if (res.ok) setAuditLog(await res.json());
    } catch {}
  };

  useEffect(() => {
    if (activeTab === 'users') fetchUsers();
    if (activeTab === 'audit') fetchAuditLog();
  }, [activeTab]);

  const handleApprove = async (id) => {
    try {
      await fetch(`${API_URL}/needy/${id}/moderation?status=approved`, { method: 'PATCH', headers: authHeader });
      fetchData();
    } catch {}
  };

  const handleReject = async (id) => {
    try {
      await fetch(`${API_URL}/needy/${id}/moderation?status=rejected`, { method: 'PATCH', headers: authHeader });
      fetchData();
    } catch {}
  };

  const handleResetRoute = async (routeId) => {
    if (!window.confirm(`Сбросить маршрут #${routeId}? Тикеты вернутся в очередь.`)) return;
    try {
      const res = await fetch(`${API_URL}/admin/routes/${routeId}/reset`, { method: 'POST', headers: authHeader });
      if (res.ok) fetchData();
      else alert('Ошибка сброса маршрута');
    } catch {}
  };

  const handleBlockUser = async (userId, isBlocked) => {
    const action = isBlocked ? 'unblock' : 'block';
    const label = isBlocked ? 'разблокировать' : 'заблокировать';
    if (!window.confirm(`${label.charAt(0).toUpperCase() + label.slice(1)} пользователя?`)) return;
    try {
      const res = await fetch(`${API_URL}/admin/users/${userId}/${action}`, { method: 'POST', headers: authHeader });
      if (res.ok) fetchUsers();
      else alert('Ошибка');
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
              <td>{item.document ? <a href={`${API_URL}/needy_uploads/${item.document.split('/').pop()}`} target="_blank" rel="noreferrer">Просмотреть</a> : '—'}</td>
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
            <div style={{ flex: 1 }}>
              <p><strong>Волонтер:</strong> {r.volunteer_name || `ID ${r.volunteer_id}`}</p>
              <p><strong>Маршрут №{r.id}</strong> — лот #{r.lot_id}</p>
              <p><strong>Начат:</strong> {new Date(r.started_at).toLocaleString()}</p>
            </div>
            <button
              className="btn-small btn-danger"
              onClick={() => handleResetRoute(r.id)}
              title="Освободить тикеты и лот, перевести маршрут в timed_out"
            >
              Сбросить
            </button>
          </div>
        ))}
      </div>
    </div>
  );

  const renderAnalytics = () => {
    const barData = [
      { name: 'Еда (кг)', value: Number(stats.kg_food_saved) || 0, color: '#4CAF50' },
      { name: 'Доставки', value: Number(stats.deliveries_completed) || 0, color: '#2196F3' },
      { name: 'Волонтёры', value: Number(stats.active_volunteers) || 0, color: '#FF9800' },
      { name: 'Ср. время (мин)', value: Math.round(Number(stats.avg_delivery_minutes)) || 0, color: '#9C27B0' },
    ];
    const expiredPct = Number(stats.percent_expired_lots) || 0;
    const pieData = [
      { name: 'Выполнено', value: Math.max(0, 100 - expiredPct), fill: '#4CAF50' },
      { name: 'Просрочено', value: expiredPct, fill: '#f44336' },
    ];
    return (
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
            <p className="big-value">{stats.avg_delivery_minutes != null ? `${Math.round(stats.avg_delivery_minutes)} мин` : '—'}</p>
          </div>
          <div className="analytic-card">
            <h4>Процент просрочки лотов</h4>
            <p className="big-value yellow-text">{stats.percent_expired_lots != null ? `${Number(stats.percent_expired_lots).toFixed(1)}%` : '—'}</p>
          </div>
        </div>

        <div className="charts-row">
          <div className="chart-box">
            <h3>Ключевые показатели</h3>
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={barData} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                <XAxis dataKey="name" tick={{ fill: '#aaa', fontSize: 12 }} />
                <YAxis tick={{ fill: '#aaa', fontSize: 12 }} />
                <Tooltip contentStyle={{ background: '#1e1e2e', border: '1px solid #333', color: '#fff' }} />
                <Bar dataKey="value" radius={[4, 4, 0, 0]}>
                  {barData.map((entry, i) => <Cell key={i} fill={entry.color} />)}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
          <div className="chart-box">
            <h3>Лоты: просрочка</h3>
            <ResponsiveContainer width="100%" height={220}>
              <PieChart>
                <Pie data={pieData} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={80} label={({ name, value }) => `${name} ${value.toFixed(1)}%`} labelLine={false}>
                  {pieData.map((entry, i) => <Cell key={i} fill={entry.fill} />)}
                </Pie>
                <Legend wrapperStyle={{ color: '#aaa', fontSize: 13 }} />
                <Tooltip contentStyle={{ background: '#1e1e2e', border: '1px solid #333', color: '#fff' }} />
              </PieChart>
            </ResponsiveContainer>
          </div>
        </div>
      </div>
    );
  };

  const ROLE_LABELS = { shop: 'Магазин', volunteer: 'Волонтёр', needy: 'Нуждающийся', admin: 'Администратор' };

  const renderUsers = () => (
    <div className="admin-tab">
      <h2>Управление пользователями</h2>
      <table className="admin-table">
        <thead>
          <tr>
            <th>Логин</th>
            <th>Роль</th>
            <th>Статус</th>
            <th>Дата регистрации</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {users.length === 0 ? (
            <tr><td colSpan="5" style={{ textAlign: 'center', padding: '20px' }}>Нет пользователей</td></tr>
          ) : users.map(u => (
            <tr key={u.id} style={{ opacity: u.is_blocked ? 0.6 : 1 }}>
              <td>{u.username}</td>
              <td>{ROLE_LABELS[u.role] || u.role}</td>
              <td>
                {u.is_blocked
                  ? <span style={{ color: '#f55' }}>Заблокирован</span>
                  : <span style={{ color: '#5f5' }}>Активен</span>}
              </td>
              <td>{new Date(u.created_at).toLocaleDateString()}</td>
              <td>
                {u.role !== 'admin' && (
                  <button
                    className={`btn-small ${u.is_blocked ? 'btn-success' : 'btn-danger'}`}
                    onClick={() => handleBlockUser(u.id, u.is_blocked)}
                  >
                    {u.is_blocked ? 'Разблокировать' : 'Заблокировать'}
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );

  const renderAuditLog = () => (
    <div className="admin-tab">
      <h2>Журнал действий</h2>
      <table className="admin-table audit-table">
        <thead>
          <tr>
            <th>Время</th>
            <th>Действие</th>
            <th>Admin</th>
            <th>Объект</th>
            <th>Детали</th>
          </tr>
        </thead>
        <tbody>
          {auditLog.length === 0 ? (
            <tr><td colSpan="5" style={{ textAlign: 'center', padding: '20px' }}>Журнал пуст</td></tr>
          ) : auditLog.map(entry => (
            <tr key={entry.id}>
              <td className="audit-time">{new Date(entry.created_at).toLocaleString()}</td>
              <td><span className="audit-action">{entry.action}</span></td>
              <td>{entry.actor_username || '—'}</td>
              <td>{entry.target_type ? `${entry.target_type} #${entry.target_id}` : '—'}</td>
              <td>{entry.details || '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );

  return (
    <div className="dashboard-container admin-container">
      <aside className="sidebar">
        <h2>SaveFood Admin</h2>
        <nav>
          <button className={activeTab === 'moderation' ? 'active' : ''} onClick={() => setActiveTab('moderation')}>Модерация</button>
          <button className={activeTab === 'dispatcher' ? 'active' : ''} onClick={() => setActiveTab('dispatcher')}>Диспетчерская</button>
          <button className={activeTab === 'users' ? 'active' : ''} onClick={() => setActiveTab('users')}>Пользователи</button>
          <button className={activeTab === 'analytics' ? 'active' : ''} onClick={() => setActiveTab('analytics')}>Аналитика</button>
          <button className={activeTab === 'audit' ? 'active' : ''} onClick={() => setActiveTab('audit')}>Логи</button>
        </nav>
      </aside>

      <main className="main-content">
        {activeTab === 'moderation' && renderModeration()}
        {activeTab === 'dispatcher' && renderDispatcher()}
        {activeTab === 'users' && renderUsers()}
        {activeTab === 'analytics' && renderAnalytics()}
        {activeTab === 'audit' && renderAuditLog()}
      </main>
    </div>
  );
};

export default AdminPanel;
