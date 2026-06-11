import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell, PieChart, Pie, Legend } from 'recharts';
import EmptyState from '../../components/EmptyState';
import { useAuth } from '../../context/AuthContext';
import { API_URL } from '../../api';
import './Admin.css';

const AdminPanel = () => {
  const { user } = useAuth();
  const { t } = useTranslation();
  const [activeTab, setActiveTab] = useState('moderation');
  const [pendingNeedy, setPendingNeedy] = useState([]);
  const [stats, setStats] = useState({});
  const [activeRoutes, setActiveRoutes] = useState([]);
  const [users, setUsers] = useState([]);
  const [auditLog, setAuditLog] = useState([]);
  const [shops, setShops] = useState([]);
  const [esgGlobal, setEsgGlobal] = useState(null);
  const [kycRechecking, setKycRechecking] = useState({});

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

  const fetchShops = async () => {
    try {
      const res = await fetch(`${API_URL}/admin/shops`, { headers: authHeader });
      if (res.ok) setShops(await res.json());
    } catch {}
  };

  useEffect(() => {
    if (activeTab === 'users') fetchUsers();
    if (activeTab === 'audit') fetchAuditLog();
    if (activeTab === 'plans') fetchShops();
    if (activeTab === 'analytics' && !esgGlobal) {
      fetch(`${API_URL}/admin/esg?months=12`, { headers: authHeader })
        .then(r => r.ok ? r.json() : null)
        .then(data => data && setEsgGlobal(data))
        .catch(() => {});
    }
  }, [activeTab]);

  const handleSetPlan = async (shopId, planValue) => {
    try {
      const res = await fetch(`${API_URL}/admin/shops/${shopId}/plan`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({ plan: planValue }),
      });
      if (res.ok) fetchShops();
      else alert(t('common.error'));
    } catch {}
  };

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
    if (!window.confirm(t('admin.confirm_reset_route', { id: routeId }))) return;
    try {
      const res = await fetch(`${API_URL}/admin/routes/${routeId}/reset`, { method: 'POST', headers: authHeader });
      if (res.ok) fetchData();
      else alert(t('admin.error_reset'));
    } catch {}
  };

  // Documents live behind the auth-checked /needy/{id}/document endpoint
  // (the public /needy_uploads mount was removed on purpose), and a plain
  // <a href> can't send the Authorization header — fetch as blob instead.
  const handleViewDocument = async (needyId) => {
    try {
      const res = await fetch(`${API_URL}/needy/${needyId}/document`, { headers: authHeader });
      if (!res.ok) { alert(t('common.error')); return; }
      const blob = await res.blob();
      window.open(URL.createObjectURL(blob), '_blank', 'noopener');
    } catch { alert(t('common.error')); }
  };

  const handleBlockUser = async (userId, isBlocked) => {
    const action = isBlocked ? 'unblock' : 'block';
    if (!window.confirm(isBlocked ? t('admin.confirm_unblock') : t('admin.confirm_block'))) return;
    try {
      const res = await fetch(`${API_URL}/admin/users/${userId}/${action}`, { method: 'POST', headers: authHeader });
      if (res.ok) fetchUsers();
      else alert(t('common.error'));
    } catch {}
  };

  // Second opinion on a disputed AI verdict: re-runs the Gemini check
  // synchronously (a few seconds) and refreshes the queue row.
  const handleKycRecheck = async (needyId) => {
    setKycRechecking(prev => ({ ...prev, [needyId]: true }));
    try {
      const res = await fetch(`${API_URL}/admin/needy/${needyId}/kyc_recheck`, { method: 'POST', headers: authHeader });
      if (!res.ok) {
        const e = await res.json().catch(() => ({}));
        alert(e.detail || t('common.error'));
        return;
      }
      await fetchData();
    } catch { alert(t('common.connection_error')); }
    finally { setKycRechecking(prev => ({ ...prev, [needyId]: false })); }
  };

  // Auto-KYC v1: AI pre-check verdict rendered as a colored hint; the
  // approve/reject decision stays with the human moderator.
  const kycBadge = (item) => {
    if (!item.kyc_verdict || item.kyc_verdict === 'unchecked') return <span style={{ opacity: 0.6 }}>—</span>;
    const colors = { likely_ok: '#5f5', review: '#fa0', likely_fraud: '#f55' };
    const labels = {
      likely_ok: t('admin.kyc_likely_ok'),
      review: t('admin.kyc_review'),
      likely_fraud: t('admin.kyc_likely_fraud'),
    };
    return (
      <span title={item.kyc_notes || ''} style={{ color: colors[item.kyc_verdict] || '#aaa', cursor: 'help' }}>
        {labels[item.kyc_verdict] || item.kyc_verdict}
        {item.kyc_score != null && ` (${Math.round(item.kyc_score * 100)}%)`}
      </span>
    );
  };

  const renderModeration = () => (
    <div className="admin-tab">
      <h2>{t('admin.moderation_queue')}</h2>
      {pendingNeedy.length === 0 ? (
        <EmptyState icon="✅" title={t('empty.moderation_title')} description={t('empty.moderation_desc')} />
      ) : (
      <table className="admin-table">
        <thead>
          <tr>
            <th>{t('admin.col_user')}</th>
            <th>{t('admin.col_contact')}</th>
            <th>{t('admin.col_document')}</th>
            <th>{t('admin.col_kyc')}</th>
            <th>{t('admin.col_actions')}</th>
          </tr>
        </thead>
        <tbody>
          {pendingNeedy.map(item => (
            <tr key={item.id}>
              <td>{item.name}</td>
              <td>{item.contact || '—'}</td>
              <td>{item.document ? <button className="btn-small" onClick={() => handleViewDocument(item.id)}>{t('admin.view_doc')}</button> : '—'}</td>
              <td>
                {kycBadge(item)}
                {item.kyc_notes && <div style={{ fontSize: '0.78rem', opacity: 0.75, maxWidth: 260 }}>{item.kyc_notes}</div>}
                {item.document && (
                  <button
                    className="btn-small"
                    style={{ marginTop: 4 }}
                    disabled={!!kycRechecking[item.id]}
                    onClick={() => handleKycRecheck(item.id)}
                  >
                    {kycRechecking[item.id] ? t('common.loading') : t('admin.kyc_recheck')}
                  </button>
                )}
              </td>
              <td>
                <button className="btn-small btn-success" onClick={() => handleApprove(item.id)}>{t('admin.approve')}</button>
                <button className="btn-small btn-danger" onClick={() => handleReject(item.id)}>{t('admin.reject')}</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      )}
    </div>
  );

  const renderPlans = () => (
    <div className="admin-tab">
      <h2>{t('admin.plans_title')}</h2>
      <p style={{ opacity: 0.75 }}>{t('admin.plans_hint')}</p>
      {shops.length === 0 ? (
        <EmptyState icon="🏪" title={t('common.no_data')} description="" />
      ) : (
      <table className="admin-table">
        <thead>
          <tr>
            <th>{t('admin.col_shop')}</th>
            <th>{t('admin.col_city')}</th>
            <th>{t('admin.col_plan')}</th>
          </tr>
        </thead>
        <tbody>
          {shops.map(s => (
            <tr key={s.id}>
              <td>{s.name}</td>
              <td>{s.city || '—'}</td>
              <td>
                <select value={s.plan || 'basic'} onChange={e => handleSetPlan(s.id, e.target.value)}>
                  <option value="basic">{t('admin.plan_basic')}</option>
                  <option value="pro">{t('admin.plan_pro')}</option>
                  <option value="enterprise">Enterprise</option>
                </select>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      )}
    </div>
  );

  const renderDispatcher = () => (
    <div className="admin-tab">
      <h2>{t('admin.dispatch')}</h2>
      <div className="incident-list">
        <h3>{t('admin.active_routes')}</h3>
        {activeRoutes.length === 0 ? (
          <EmptyState icon="🗺️" title={t('empty.routes_title')} description={t('empty.routes_desc')} />
        ) : activeRoutes.map(r => (
          <div key={r.id} className="incident-card">
            <div style={{ flex: 1 }}>
              <p><strong>{t('admin.volunteer_label')}:</strong> {r.volunteer_name || `ID ${r.volunteer_id}`}</p>
              <p><strong>{t('admin.route_label')} №{r.id}</strong> — {t('shop.lots')} #{r.lot_id}</p>
              <p><strong>{t('admin.started')}:</strong> {new Date(r.started_at).toLocaleString()}</p>
            </div>
            <button className="btn-small btn-danger" onClick={() => handleResetRoute(r.id)}>
              {t('admin.reset_route')}
            </button>
          </div>
        ))}
      </div>
    </div>
  );

  const renderAnalytics = () => {
    const barData = [
      { name: t('admin.analytics_food_label'), value: Number(stats.kg_food_saved) || 0, color: '#4CAF50' },
      { name: t('admin.analytics_deliveries_label'), value: Number(stats.deliveries_completed) || 0, color: '#2196F3' },
      { name: t('admin.analytics_volunteers_label'), value: Number(stats.active_volunteers) || 0, color: '#FF9800' },
      { name: t('admin.analytics_time_label'), value: Math.round(Number(stats.avg_delivery_minutes)) || 0, color: '#9C27B0' },
    ];
    const expiredPct = Number(stats.percent_expired_lots) || 0;
    const pieData = [
      { name: t('admin.pie_completed'), value: Math.max(0, 100 - expiredPct), fill: '#4CAF50' },
      { name: t('admin.pie_expired'), value: expiredPct, fill: '#f44336' },
    ];
    return (
      <div className="admin-tab">
        <h2>{t('admin.analytics_title')}</h2>
        <div className="analytics-grid">
          <div className="analytic-card">
            <h4>{t('admin.food_saved')}</h4>
            <p className="big-value">{stats.kg_food_saved ?? '—'}</p>
          </div>
          <div className="analytic-card">
            <h4>{t('admin.deliveries')}</h4>
            <p className="big-value">{stats.deliveries_completed ?? '—'}</p>
          </div>
          <div className="analytic-card">
            <h4>{t('admin.active_vols')}</h4>
            <p className="big-value">{stats.active_volunteers ?? '—'}</p>
          </div>
          <div className="analytic-card">
            <h4>{t('admin.avg_delivery')}</h4>
            <p className="big-value">{stats.avg_delivery_minutes != null ? `${Math.round(stats.avg_delivery_minutes)} ${t('admin.min')}` : '—'}</p>
          </div>
          <div className="analytic-card">
            <h4>{t('admin.expired_pct')}</h4>
            <p className="big-value yellow-text">{stats.percent_expired_lots != null ? `${Number(stats.percent_expired_lots).toFixed(1)}%` : '—'}</p>
          </div>
        </div>

        {esgGlobal && (
          <>
            <h3 style={{ marginTop: 24 }}>{t('admin.esg_title')}</h3>
            <div className="analytics-grid">
              <div className="analytic-card">
                <h4>{t('admin.esg_co2')}</h4>
                <p className="big-value">{esgGlobal.totals.co2_kg} {t('shop.kg')}</p>
              </div>
              <div className="analytic-card">
                <h4>{t('admin.esg_meals')}</h4>
                <p className="big-value">{esgGlobal.totals.meals}</p>
              </div>
              {(esgGlobal.top_shops || []).slice(0, 3).map(s => (
                <div className="analytic-card" key={s.id}>
                  <h4>🏪 {s.name}</h4>
                  <p className="big-value">{s.kg} {t('shop.kg')}</p>
                </div>
              ))}
            </div>
          </>
        )}

        <div className="charts-row">
          <div className="chart-box">
            <h3>{t('admin.chart_key_metrics')}</h3>
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
            <h3>{t('admin.chart_expired_lots')}</h3>
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

  const renderUsers = () => (
    <div className="admin-tab">
      <h2>{t('admin.users')}</h2>
      <table className="admin-table">
        <thead>
          <tr>
            <th>{t('admin.col_login')}</th>
            <th>{t('admin.col_role')}</th>
            <th>{t('admin.col_status')}</th>
            <th>{t('admin.col_created')}</th>
            <th>{t('admin.col_actions')}</th>
          </tr>
        </thead>
        <tbody>
          {users.length === 0 ? (
            <tr><td colSpan="5" style={{ textAlign: 'center', padding: '20px' }}>{t('common.no_data')}</td></tr>
          ) : users.map(u => (
            <tr key={u.id} style={{ opacity: u.is_blocked ? 0.6 : 1 }}>
              <td>{u.username}</td>
              <td>{t(`nav.roles.${u.role}`) || u.role}</td>
              <td>
                {u.is_blocked
                  ? <span style={{ color: '#f55' }}>{t('admin.blocked')}</span>
                  : <span style={{ color: '#5f5' }}>{t('admin.active')}</span>}
              </td>
              <td>{new Date(u.created_at).toLocaleDateString()}</td>
              <td>
                {u.role !== 'admin' && (
                  <button
                    className={`btn-small ${u.is_blocked ? 'btn-success' : 'btn-danger'}`}
                    onClick={() => handleBlockUser(u.id, u.is_blocked)}
                  >
                    {u.is_blocked ? t('admin.unblock') : t('admin.block')}
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
      <h2>{t('admin.logs')}</h2>
      {auditLog.length === 0 ? (
        <EmptyState icon="📋" title={t('empty.history_title')} description={t('empty.history_desc')} />
      ) : (
      <table className="admin-table audit-table">
        <thead>
          <tr>
            <th>{t('admin.log_time')}</th>
            <th>{t('admin.log_action')}</th>
            <th>{t('admin.log_admin')}</th>
            <th>{t('admin.log_object')}</th>
            <th>{t('admin.log_details')}</th>
          </tr>
        </thead>
        <tbody>
          {auditLog.map(entry => (
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
      )}
    </div>
  );

  return (
    <div className="dashboard-container admin-container">
      <aside className="sidebar">
        <h2>SaveFood Admin</h2>
        <nav>
          <button className={activeTab === 'moderation' ? 'active' : ''} onClick={() => setActiveTab('moderation')}>{t('admin.moderation')}</button>
          <button className={activeTab === 'dispatcher' ? 'active' : ''} onClick={() => setActiveTab('dispatcher')}>{t('admin.dispatch')}</button>
          <button className={activeTab === 'users' ? 'active' : ''} onClick={() => setActiveTab('users')}>{t('admin.users')}</button>
          <button className={activeTab === 'plans' ? 'active' : ''} onClick={() => setActiveTab('plans')}>{t('admin.plans')}</button>
          <button className={activeTab === 'analytics' ? 'active' : ''} onClick={() => setActiveTab('analytics')}>{t('admin.analytics')}</button>
          <button className={activeTab === 'audit' ? 'active' : ''} onClick={() => setActiveTab('audit')}>{t('admin.logs')}</button>
        </nav>
      </aside>

      <main className="main-content">
        {activeTab === 'moderation' && renderModeration()}
        {activeTab === 'dispatcher' && renderDispatcher()}
        {activeTab === 'users' && renderUsers()}
        {activeTab === 'plans' && renderPlans()}
        {activeTab === 'analytics' && renderAnalytics()}
        {activeTab === 'audit' && renderAuditLog()}
      </main>
    </div>
  );
};

export default AdminPanel;
