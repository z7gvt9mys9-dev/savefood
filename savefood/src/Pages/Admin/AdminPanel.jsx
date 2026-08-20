import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell, PieChart, Pie, Legend } from 'recharts';
import EmptyState from '../../components/EmptyState';
import MonoIcon from '../../components/MonoIcon';
import { useAuth } from '../../context/AuthContext';
import { API_URL, authFetch } from '../../api';
import './Admin.css';

/** An admin image endpoint requires Bearer auth, which a plain <img> cannot send. */
const ProtectedDeliveryPhoto = ({ path }) => {
  const [objectUrl, setObjectUrl] = useState(null);

  useEffect(() => {
    if (!path) {
      setObjectUrl(null);
      return undefined;
    }
    let cancelled = false;
    let createdUrl = null;
    setObjectUrl(null);
    authFetch(`${API_URL}${path}`)
      .then(res => {
        if (!res.ok) throw new Error('photo unavailable');
        return res.blob();
      })
      .then(blob => {
        createdUrl = URL.createObjectURL(blob);
        if (cancelled) URL.revokeObjectURL(createdUrl);
        else setObjectUrl(createdUrl);
      })
      .catch(() => { if (!cancelled) setObjectUrl(null); });
    return () => {
      cancelled = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [path]);

  if (!objectUrl) return <div className="photo-mod-img" aria-label="Photo unavailable" />;
  return (
    <a href={objectUrl} target="_blank" rel="noopener noreferrer">
      <img src={objectUrl} alt="Delivery proof" className="photo-mod-img" />
    </a>
  );
};

const AdminPanel = () => {
  const { user } = useAuth();
  const { t } = useTranslation();
  const [activeTab, setActiveTab] = useState('photos');
  const [stats, setStats] = useState({});
  const [activeRoutes, setActiveRoutes] = useState([]);
  const [users, setUsers] = useState([]);
  const [auditLog, setAuditLog] = useState([]);
  const [shops, setShops] = useState([]);
  const [esgGlobal, setEsgGlobal] = useState(null);
  const [deliveryPhotos, setDeliveryPhotos] = useState([]);
  const [photoBusy, setPhotoBusy] = useState({});
  const [heatmap, setHeatmap] = useState(null);
  // Volunteer identity KYC only; recipients do not participate in moderation.
  const [kycQueue, setKycQueue] = useState([]);
  const [kycBusy, setKycBusy] = useState({});

  const authHeader = {};

  const fetchData = async () => {
    try {
      const [statsRes, routesRes] = await Promise.all([
        authFetch(`${API_URL}/admin/stats`, { headers: authHeader }),
        authFetch(`${API_URL}/admin/routes`, { headers: authHeader }),
      ]);
      if (statsRes.ok) setStats(await statsRes.json());
      if (routesRes.ok) setActiveRoutes(await routesRes.json());
    } catch {}
  };

  const fetchUsers = async () => {
    try {
      const res = await authFetch(`${API_URL}/admin/users`, { headers: authHeader });
      if (res.ok) setUsers(await res.json());
    } catch {}
  };

  useEffect(() => {
    fetchData();
  }, []);

  const fetchAuditLog = async () => {
    try {
      const res = await authFetch(`${API_URL}/admin/audit?limit=50&offset=0`, { headers: authHeader });
      if (res.ok) setAuditLog(await res.json());
    } catch {}
  };

  const fetchShops = async () => {
    try {
      const res = await authFetch(`${API_URL}/admin/shops`, { headers: authHeader });
      if (res.ok) setShops(await res.json());
    } catch {}
  };

  const fetchDeliveryPhotos = async () => {
    try {
      const res = await authFetch(`${API_URL}/admin/delivery_photos?status=pending`, { headers: authHeader });
      if (res.ok) setDeliveryPhotos(await res.json());
    } catch {}
  };

  const fetchKycQueue = async () => {
    try {
      const res = await authFetch(`${API_URL}/admin/volunteers?status=pending`, { headers: authHeader });
      setKycQueue(res.ok ? await res.json() : []);
    } catch {}
  };

  useEffect(() => {
    if (activeTab === 'users') fetchUsers();
    if (activeTab === 'audit') fetchAuditLog();
    if (activeTab === 'plans') fetchShops();
    if (activeTab === 'photos') fetchDeliveryPhotos();
    if (activeTab === 'kyc') fetchKycQueue();
    if (activeTab === 'analytics' && !esgGlobal) {
      authFetch(`${API_URL}/admin/esg?months=12`, { headers: authHeader })
        .then(r => r.ok ? r.json() : null)
        .then(data => data && setEsgGlobal(data))
        .catch(() => {});
    }
    if (activeTab === 'analytics' && !heatmap) {
      authFetch(`${API_URL}/admin/heatmap`, { headers: authHeader })
        .then(r => r.ok ? r.json() : null)
        .then(data => data && setHeatmap(data))
        .catch(() => {});
    }
  }, [activeTab]);

  const handleSetPlan = async (shopId, planValue) => {
    try {
      const res = await authFetch(`${API_URL}/admin/shops/${shopId}/plan`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({ plan: planValue }),
      });
      if (res.ok) fetchShops();
      else alert(t('common.error'));
    } catch {}
  };

  const handleResetRoute = async (routeId) => {
    if (!window.confirm(t('admin.confirm_reset_route', { id: routeId }))) return;
    try {
      const res = await authFetch(`${API_URL}/admin/routes/${routeId}/reset`, { method: 'POST', headers: authHeader });
      if (res.ok) fetchData();
      else alert(t('admin.error_reset'));
    } catch {}
  };

  const handleBlockUser = async (userId, isBlocked) => {
    const action = isBlocked ? 'unblock' : 'block';
    if (!window.confirm(isBlocked ? t('admin.confirm_unblock') : t('admin.confirm_block'))) return;
    try {
      const res = await authFetch(`${API_URL}/admin/users/${userId}/${action}`, { method: 'POST', headers: authHeader });
      if (res.ok) fetchUsers();
      else alert(t('common.error'));
    } catch {}
  };

  // Delivery photo moderation: publish or drop a recipient photo before it
  // reaches the public Impact feed.
  const handleModeratePhoto = async (photo, action) => {
    const ticketId = photo.ticket_id;
    if (!photo.photo_ref) {
      await fetchDeliveryPhotos();
      alert(t('common.error'));
      return;
    }
    setPhotoBusy(prev => ({ ...prev, [ticketId]: true }));
    try {
      const ref = encodeURIComponent(photo.photo_ref);
      const res = await authFetch(
        `${API_URL}/admin/delivery_photos/${ticketId}/${action}?photo_ref=${ref}`,
        { method: 'POST', headers: authHeader },
      );
      if (res.ok) setDeliveryPhotos(prev => prev.filter(p => p.ticket_id !== ticketId));
      else {
        // A replacement was uploaded while this card was open. Refresh rather
        // than applying a decision to the wrong proof image.
        await fetchDeliveryPhotos();
        alert(t('common.error'));
      }
    } catch { alert(t('common.connection_error')); }
    finally { setPhotoBusy(prev => ({ ...prev, [ticketId]: false })); }
  };

  // AI "is this food?" pre-check verdict as a colored hint; the publish
  // decision stays with the human moderator.
  const photoBadge = (item) => {
    const v = item.delivery_photo_ai_verdict;
    if (!v || v === 'unchecked') return <span style={{ opacity: 0.6 }}>{t('admin.photo_unchecked')}</span>;
    const colors = { food: '#5f5', review: '#fa0', not_food: '#f55', inappropriate: '#f55' };
    const labels = {
      food: t('admin.photo_food'),
      review: t('admin.photo_review'),
      not_food: t('admin.photo_not_food'),
      inappropriate: t('admin.photo_inappropriate'),
    };
    return (
      <span style={{ color: colors[v] || '#aaa' }}>
        {labels[v] || v}
        {item.delivery_photo_ai_score != null && ` (${Math.round(item.delivery_photo_ai_score * 100)}%)`}
      </span>
    );
  };

  // Manual KYC decision (§5). Auto-KYC settles the confident cases; this is the
  // escape hatch for everything it flagged `review`, plus overturning a wrong
  // automatic verdict. The document itself is never shown — by design (§58.1),
  // the moderator judges from what the AI extracted.
  const handleModerateKyc = async (id, status) => {
    const key = `volunteer:${id}`;
    setKycBusy(prev => ({ ...prev, [key]: true }));
    try {
      const res = await authFetch(`${API_URL}/admin/volunteers/${id}/moderation`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({ status }),
      });
      if (res.ok) fetchKycQueue();
      else alert(t('common.error'));
    } catch { alert(t('common.connection_error')); }
    finally { setKycBusy(prev => ({ ...prev, [key]: false })); }
  };

  const kycBadge = (item) => {
    const v = item.kyc_verdict;
    if (!v || v === 'unchecked') return <span style={{ opacity: 0.6 }}>{t('admin.kyc_unchecked')}</span>;
    const colors = { likely_ok: '#5f5', review: '#fa0', likely_fraud: '#f55' };
    const labels = {
      likely_ok: t('admin.kyc_likely_ok'),
      review: t('admin.kyc_review'),
      likely_fraud: t('admin.kyc_likely_fraud'),
    };
    return (
      <span style={{ color: colors[v] || '#aaa' }}>
        {labels[v] || v}
        {item.kyc_score != null && ` (${Math.round(item.kyc_score * 100)}%)`}
      </span>
    );
  };

  const renderKycGroup = (rows) => (
    <>
      <h3>{t('admin.kyc_volunteers')} ({rows.length})</h3>
      {rows.length === 0 ? (
        <p style={{ opacity: 0.6 }}>{t('admin.kyc_empty_group')}</p>
      ) : (
        <div className="photo-mod-grid">
          {rows.map(item => {
            const key = `volunteer:${item.id}`;
            return (
              <div key={key} className="photo-mod-card">
                <div className="photo-mod-meta">
                  <div><strong>{item.name || `#${item.id}`}</strong></div>
                  <div>{kycBadge(item)}</div>
                  {item.kyc_notes && (
                    <div style={{ fontSize: '0.78rem', opacity: 0.75 }}>{item.kyc_notes}</div>
                  )}
                  <div style={{ fontSize: '0.78rem', opacity: 0.6 }}>
                    {item.city || '—'}
                  </div>
                  <div style={{ fontSize: '0.78rem', opacity: 0.6 }}>
                    <MonoIcon name={item.has_document ? 'paperclip' : 'warning'} />{' '}
                    {item.has_document ? t('admin.kyc_doc_present') : t('admin.kyc_doc_missing')}
                  </div>
                </div>
                <div className="photo-mod-actions">
                  <button className="btn-small btn-success" disabled={!!kycBusy[key] || !item.has_document}
                    onClick={() => handleModerateKyc(item.id, 'approved')}>{t('admin.approve')}</button>
                  <button className="btn-small btn-danger" disabled={!!kycBusy[key]}
                    onClick={() => handleModerateKyc(item.id, 'rejected')}>{t('admin.reject')}</button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </>
  );

  const renderKyc = () => {
    const total = kycQueue.length;
    return (
      <div className="admin-tab">
        <h2>{t('admin.moderation_queue')}</h2>
        <p style={{ opacity: 0.75 }}>{t('admin.kyc_hint')}</p>
        {total === 0 ? (
          <EmptyState icon={<MonoIcon name="folder" />} title={t('empty.moderation_title')} description={t('empty.moderation_desc')} />
        ) : (
          <>
            {renderKycGroup(kycQueue)}
          </>
        )}
      </div>
    );
  };

  const renderPhotos = () => (
    <div className="admin-tab">
      <h2>{t('admin.photo_queue')}</h2>
      <p style={{ opacity: 0.75 }}>{t('admin.photo_hint')}</p>
      {deliveryPhotos.length === 0 ? (
        <EmptyState icon={<MonoIcon name="camera" />} title={t('empty.photos_title')} description={t('empty.photos_desc')} />
      ) : (
      <div className="photo-mod-grid">
        {deliveryPhotos.map(p => (
          <div key={p.ticket_id} className="photo-mod-card">
            <ProtectedDeliveryPhoto path={p.photo_url} />
            <div className="photo-mod-meta">
              <div>{photoBadge(p)}</div>
              {p.delivery_photo_ai_notes && (
                <div style={{ fontSize: '0.78rem', opacity: 0.75 }}>{p.delivery_photo_ai_notes}</div>
              )}
              <div style={{ fontSize: '0.78rem', opacity: 0.6 }}>
                {[p.category, p.city].filter(Boolean).join(' · ') || '—'}
              </div>
            </div>
            <div className="photo-mod-actions">
              <button className="btn-small btn-success" disabled={!!photoBusy[p.ticket_id]}
                onClick={() => handleModeratePhoto(p, 'approve')}>{t('admin.publish')}</button>
              <button className="btn-small btn-danger" disabled={!!photoBusy[p.ticket_id]}
                onClick={() => handleModeratePhoto(p, 'reject')}>{t('admin.reject')}</button>
            </div>
          </div>
        ))}
      </div>
      )}
    </div>
  );

  const renderPlans = () => (
    <div className="admin-tab">
      <h2>{t('admin.plans_title')}</h2>
      <p style={{ opacity: 0.75 }}>{t('admin.plans_hint')}</p>
      {shops.length === 0 ? (
        <EmptyState icon={<MonoIcon name="store" />} title={t('common.no_data')} description="" />
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
          <EmptyState icon={<MonoIcon name="map" />} title={t('empty.routes_title')} description={t('empty.routes_desc')} />
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
                  <h4><MonoIcon name="store" /> {s.name}</h4>
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

        {heatmap && heatmap.length > 0 && (
          <>
            <h3 style={{ marginTop: 24 }}>{t('admin.heatmap')}</h3>
            <table className="admin-table">
              <thead>
                <tr>
                  <th>{t('admin.heatmap_city')}</th>
                  <th>{t('admin.heatmap_lots')}</th>
                  <th>{t('admin.heatmap_tickets')}</th>
                  <th>{t('admin.heatmap_needy')}</th>
                  <th>{t('admin.heatmap_vols')}</th>
                  <th>{t('admin.heatmap_available')}</th>
                  <th>{t('admin.heatmap_gap')}</th>
                </tr>
              </thead>
              <tbody>
                {heatmap.map(r => (
                  <tr key={r.city}>
                    <td>{r.city}</td>
                    <td>{r.active_lots}</td>
                    <td>{r.open_tickets}</td>
                    <td>{r.active_needy}</td>
                    <td>{r.volunteers}</td>
                    <td>{r.volunteers_available}</td>
                    <td style={{ fontWeight: 700, color: r.gap > 0 ? '#f44336' : (r.gap < 0 ? '#4CAF50' : '#aaa') }}>
                      {r.gap > 0 ? `+${r.gap}` : r.gap}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
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
        <EmptyState icon={<MonoIcon name="clipboard" />} title={t('empty.history_title')} description={t('empty.history_desc')} />
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
          <button className={activeTab === 'kyc' ? 'active' : ''} onClick={() => setActiveTab('kyc')}>{t('admin.moderation')}</button>
          <button className={activeTab === 'photos' ? 'active' : ''} onClick={() => setActiveTab('photos')}>{t('admin.photos')}</button>
          <button className={activeTab === 'dispatcher' ? 'active' : ''} onClick={() => setActiveTab('dispatcher')}>{t('admin.dispatch')}</button>
          <button className={activeTab === 'users' ? 'active' : ''} onClick={() => setActiveTab('users')}>{t('admin.users')}</button>
          <button className={activeTab === 'plans' ? 'active' : ''} onClick={() => setActiveTab('plans')}>{t('admin.plans')}</button>
          <button className={activeTab === 'analytics' ? 'active' : ''} onClick={() => setActiveTab('analytics')}>{t('admin.analytics')}</button>
          <button className={activeTab === 'audit' ? 'active' : ''} onClick={() => setActiveTab('audit')}>{t('admin.logs')}</button>
        </nav>
      </aside>

      <main className="main-content">
        {activeTab === 'kyc' && renderKyc()}
        {activeTab === 'photos' && renderPhotos()}
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
