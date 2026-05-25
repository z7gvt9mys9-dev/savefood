import React, { useState } from 'react';
import './Admin.css';

const AdminPanel = () => {
  const [activeTab, setActiveTab] = useState('moderation');

  const moderationQueue = [
    { id: 1, name: 'Иван Петров', type: 'Многодетный', doc: 'spravka.pdf' },
    { id: 2, name: 'Мария Сидорова', type: 'Малоимущая', doc: 'passport.jpg' },
  ];

  const renderModeration = () => (
    <div className="admin-tab">
      <h2>Очередь модерации (24ч)</h2>
      <table className="admin-table">
        <thead>
          <tr>
            <th>Пользователь</th>
            <th>Статус</th>
            <th>Документ</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          {moderationQueue.map(item => (
            <tr key={item.id}>
              <td>{item.name}</td>
              <td>{item.type}</td>
              <td><a href="#" onClick={(e) => e.preventDefault()}>Просмотреть</a></td>
              <td>
                <button className="btn-small btn-success">Одобрить</button>
                <button className="btn-small btn-danger">Отклонить</button>
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
      <div className="map-placeholder">
        <div className="map-mock">
          <div className="incident-marker" title="Волонтер не на связи 35 мин">⚠️</div>
          <div className="active-route-line"></div>
        </div>
      </div>
      <div className="incident-list">
        <h3>Проблемные зоны</h3>
        <div className="incident-card warning">
          <p><strong>Волонтер:</strong> Алексей К.</p>
          <p><strong>Проблема:</strong> Не выходит на связь > 30 мин</p>
          <button className="btn-small">Связаться</button>
          <button className="btn-small btn-secondary">Переназначить</button>
        </div>
      </div>
    </div>
  );

  const renderAnalytics = () => (
    <div className="admin-tab">
      <h2>Аналитика эффективности</h2>
      <div className="analytics-grid">
        <div className="analytic-card">
          <h4>Среднее время доставки</h4>
          <p className="big-value">28 мин</p>
        </div>
        <div className="analytic-card">
          <h4>Процент просрочки лотов</h4>
          <p className="big-value yellow-text">4.2%</p>
        </div>
        <div className="analytic-card">
          <h4>Активных волонтеров</h4>
          <p className="big-value">156</p>
        </div>
      </div>
      <button className="btn btn-secondary">Выгрузить отчет (XLSX)</button>
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
