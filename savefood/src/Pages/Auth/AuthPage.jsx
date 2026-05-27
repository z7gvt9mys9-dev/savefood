import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { API_URL } from '../../api';
import AddressInput from './AddressInput';
import './Auth.css';

const AuthPage = () => {
  const [isLogin, setIsLogin] = useState(true);
  const [role, setRole] = useState('shop'); // shop, volunteer, needy
  const [step, setStep] = useState(1); // For multi-step registration (Needy)
  const [tgStep, setTgStep] = useState(false); // show Telegram link step after registration
  const [regToken, setRegToken] = useState(null); // token after registration

  const [formData, setFormData] = useState({
    email: '',
    password: '',
    phone: '',
    name: '',
    contact: '',
    legalData: '',
    address: '',
    lat: null,
    lon: null,
    familySize: 1,
    preferences: '',
    urgency: 'normal',
    document: null
  });

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    setFormData({ ...formData, [name]: value });
  };

  const handleAddressChange = (addr) => {
    setFormData({ ...formData, address: addr.address, lat: addr.lat, lon: addr.lon, city: addr.city });
  };

  const { login } = useAuth();
  const navigate = useNavigate();

  const [agreed, setAgreed] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!isLogin && !agreed) {
      alert("Необходимо согласие на обработку персональных данных");
      return;
    }

    if (isLogin) {
      try {
        const fd = new FormData();
        fd.append('username', (role === 'shop' || role === 'admin') ? formData.email : formData.phone);
        fd.append('password', formData.password);
        const res = await fetch(`${API_URL}/auth/login`, { method: 'POST', body: fd });
        if (!res.ok) throw new Error('Неверный логин или пароль');
        const data = await res.json();
        login(data.access_token, data.role, data.related_id);
        navigate(`/${data.role}`);
      } catch (err) {
        alert(err.message);
      }
    } else {
      try {
        let endpoint = '';
        let body = {};
        if (role === 'shop') {
          endpoint = `${API_URL}/shops/register`;
          body = { name: formData.name, contact: formData.contact, lat: formData.lat, lon: formData.lon, city: formData.city, username: formData.email, password: formData.password };
        } else if (role === 'volunteer') {
          endpoint = `${API_URL}/volunteers/register`;
          body = { name: formData.name, contact: formData.phone, city: formData.city, username: formData.phone, password: formData.password };
        } else {
          endpoint = `${API_URL}/needy/register`;
          body = { name: formData.name, contact: formData.phone, username: formData.phone, password: formData.password };
        }
        const res = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        if (!res.ok) {
          const err = await res.json();
          throw new Error(err.detail || 'Ошибка регистрации');
        }
        const data = await res.json();
        if (role === 'needy' && formData.document && data.id) {
          const fd = new FormData();
          fd.append('file', formData.document);
          await fetch(`${API_URL}/needy/${data.id}/profile/upload`, {
            method: 'POST',
            body: fd,
          }).catch(() => {});
        }
        // Auto-login to get token for TG linking
        try {
          const loginUsername = (role === 'shop') ? formData.email : formData.phone;
          const fd = new FormData();
          fd.append('username', loginUsername);
          fd.append('password', formData.password);
          const loginRes = await fetch(`${API_URL}/auth/login`, { method: 'POST', body: fd });
          if (loginRes.ok) {
            const loginData = await loginRes.json();
            setRegToken(loginData.access_token);
          }
        } catch {}
        setTgStep(true);
      } catch (err) {
        alert(err.message);
      }
    }
  };

  const renderRoleSelection = () => (
    <div className="role-selector">
      <button 
        className={`role-btn ${role === 'shop' ? 'active' : ''}`} 
        onClick={() => { setRole('shop'); setStep(1); }}
      >
        Магазин
      </button>
      <button 
        className={`role-btn ${role === 'volunteer' ? 'active' : ''}`} 
        onClick={() => { setRole('volunteer'); setStep(1); }}
      >
        Волонтер
      </button>
      <button
        className={`role-btn ${role === 'needy' ? 'active' : ''}`}
        onClick={() => { setRole('needy'); setStep(1); }}
      >
        Нуждающийся
      </button>
      <button
        className={`role-btn ${role === 'admin' ? 'active' : ''}`}
        onClick={() => { setRole('admin'); setStep(1); }}
      >
        Администратор
      </button>
    </div>
  );

  const renderLoginForm = () => (
    <form onSubmit={handleSubmit} className="auth-form">
      <h2>Вход: {role === 'shop' ? 'Магазин' : role === 'volunteer' ? 'Волонтер' : role === 'admin' ? 'Администратор' : 'Нуждающийся'}</h2>

      {role === 'shop' || role === 'admin' ? (
        <>
          <input type="email" name="email" placeholder="Email" onChange={handleInputChange} required />
          <input type="password" name="password" placeholder="Пароль" onChange={handleInputChange} required />
        </>
      ) : (
        <>
          <input type="tel" name="phone" placeholder="Номер телефона" onChange={handleInputChange} required />
          <input type="password" name="password" placeholder="Пароль" onChange={handleInputChange} required />
        </>
      )}

      <button type="submit" className="btn btn-primary">Войти</button>
      {role !== 'admin' && (
        <p onClick={() => setIsLogin(false)} className="toggle-auth">Нет аккаунта? Зарегистрироваться</p>
      )}
    </form>
  );

  const renderShopReg = () => (
    <form onSubmit={handleSubmit} className="auth-form">
      <h2>Регистрация Магазина</h2>
      <input type="text" name="name" placeholder="Название магазина/кафе" onChange={handleInputChange} required />
      <input type="text" name="legalData" placeholder="Юридические данные (ИНН/ОГРН)" onChange={handleInputChange} required />
      <input type="text" name="contact" placeholder="Контактное лицо / Телефон" onChange={handleInputChange} required />
      <input type="email" name="email" placeholder="Email для входа" onChange={handleInputChange} required />
      <input type="password" name="password" placeholder="Пароль" onChange={handleInputChange} required />
      
      <AddressInput 
        label="Фактический адрес объекта" 
        onChange={handleAddressChange} 
        value={formData.address}
      />
      
      <div className="consent-box">
        <label className="checkbox-label">
          <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} required />
          <span>Я согласен с <a href="#" onClick={(e) => { e.preventDefault(); alert('Политика конфиденциальности: Мы удаляем ваши документы сразу после проверки и не передаем данные третьим лицам.'); }}>политикой конфиденциальности</a> и обработкой данных</span>
        </label>
      </div>

      <div className="info-box">
        <p>После входа вы сможете формировать лоты списаний и настраивать окна выдачи.</p>
      </div>

      <button type="submit" className="btn btn-primary">Зарегистрироваться</button>
      <p onClick={() => setIsLogin(true)} className="toggle-auth">Уже есть аккаунт? Войти</p>
    </form>
  );

  const renderVolunteerReg = () => (
    <form onSubmit={handleSubmit} className="auth-form">
      <h2>Стать Волонтером</h2>
      <input type="text" name="name" placeholder="Ваше имя" onChange={handleInputChange} required />
      <input type="tel" name="phone" placeholder="Номер телефона" onChange={handleInputChange} required />
      <input type="password" name="password" placeholder="Придумайте пароль" onChange={handleInputChange} required />

      <AddressInput
        label="Ваш город / адрес (для зоны доставки)"
        onChange={handleAddressChange}
        value={formData.address}
      />

      <div className="consent-box">
        <label className="checkbox-label">
          <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} required />
          <span>Я согласен с политикой конфиденциальности</span>
        </label>
      </div>

      <div className="gps-notice">
        <p>⚠️ Для работы волонтером <strong>необходим доступ к геолокации</strong>. Это нужно для навигации и подтверждения доставки.</p>
      </div>

      <button type="submit" className="btn btn-primary">Зарегистрироваться</button>
      <p onClick={() => setIsLogin(true)} className="toggle-auth">Уже есть аккаунт? Войти</p>
    </form>
  );

  const renderNeedyReg = () => {
    if (step === 1) {
      return (
        <div className="auth-form">
          <h2>Регистрация (Шаг 1 из 3)</h2>
          <p className="subtitle">Первичная регистрация и подача документов</p>
          <input type="text" name="name" placeholder="ФИО" onChange={handleInputChange} required />
          <input type="tel" name="phone" placeholder="Номер телефона" onChange={handleInputChange} required />
          <input type="password" name="password" placeholder="Придумайте пароль" onChange={handleInputChange} required />

          <div className="file-upload">
            <label>Документ, подтверждающий статус (Многодетность/Малоимущность)</label>
            <input type="file" onChange={(e) => setFormData({...formData, document: e.target.files[0]})} required />
          </div>

          <div className="consent-box">
            <label className="checkbox-label">
              <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} required />
              <span>Я согласен на обработку моих документов. Они будут удалены сразу после проверки.</span>
            </label>
          </div>

          <button onClick={() => setStep(2)} className="btn btn-primary">Далее</button>
          <p onClick={() => setIsLogin(true)} className="toggle-auth">Уже есть аккаунт? Войти</p>
        </div>
      );
    }
    if (step === 2) {
      return (
        <div className="auth-form">
          <h2>Модерация (Шаг 2 из 3)</h2>
          <div className="moderation-box">
            <div className="spinner"></div>
            <p>Ваши документы отправлены на проверку.</p>
            <p className="hint">Обычно это занимает не более 24 часов.</p>
          </div>
          <button onClick={() => setStep(3)} className="btn btn-secondary">Имитировать одобрение</button>
        </div>
      );
    }
    return (
      <form onSubmit={handleSubmit} className="auth-form">
        <h2>Анкета (Шаг 3 из 3)</h2>
        <AddressInput 
          label="Точный адрес проживания" 
          onChange={handleAddressChange} 
          value={formData.address}
        />
        <input type="number" name="familySize" placeholder="Количество членов семьи" onChange={handleInputChange} required />
        <textarea name="preferences" placeholder="Пищевые ограничения или предпочтения" onChange={handleInputChange}></textarea>
        
        <label>Срочность заявки</label>
        <select name="urgency" onChange={handleInputChange}>
          <option value="normal">Обычная</option>
          <option value="high">Высокая</option>
          <option value="critical">Критическая</option>
        </select>

        <div className="limit-notice">
          <p>ℹ️ Помощь можно получать <strong>не чаще одного раза в неделю</strong>.</p>
        </div>

        <button type="submit" className="btn btn-primary">Завершить регистрацию</button>
      </form>
    );
  };

  const renderTgStep = () => (
    <div className="auth-form">
      <h2>Привяжите Telegram для уведомлений</h2>
      <p style={{ color: '#aaa', marginBottom: 20 }}>Получайте уведомления о доставках прямо в Telegram</p>
      {regToken && (
        <button
          className="btn btn-primary"
          style={{ marginBottom: 12 }}
          onClick={async () => {
            try {
              const res = await fetch(`${API_URL}/auth/telegram/init-link`, {
                headers: { Authorization: `Bearer ${regToken}` },
              });
              if (!res.ok) { alert('Ошибка генерации ссылки'); return; }
              const data = await res.json();
              window.open(data.link, '_blank');
            } catch { alert('Ошибка подключения'); }
          }}
        >
          Открыть Telegram
        </button>
      )}
      <button
        className="btn btn-secondary"
        onClick={() => { setTgStep(false); setIsLogin(true); }}
      >
        Пропустить
      </button>
    </div>
  );

  return (
    <div className="auth-container">
      <div className="auth-card">
        {!tgStep && renderRoleSelection()}
        {tgStep ? renderTgStep() : (
          isLogin ? renderLoginForm() : (
            role === 'shop' ? renderShopReg() :
            role === 'volunteer' ? renderVolunteerReg() :
            renderNeedyReg()
          )
        )}
      </div>
    </div>
  );
};

export default AuthPage;
