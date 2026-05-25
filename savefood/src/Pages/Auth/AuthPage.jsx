import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import AddressInput from './AddressInput';
import './Auth.css';

const AuthPage = () => {
  const [isLogin, setIsLogin] = useState(true);
  const [role, setRole] = useState('shop'); // shop, volunteer, needy
  const [step, setStep] = useState(1); // For multi-step registration (Needy)

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
    setFormData({ ...formData, address: addr.address, lat: addr.lat, lon: addr.lon });
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
        const formDataLogin = new FormData();
        formDataLogin.append('username', role === 'shop' ? formData.email : formData.phone);
        formDataLogin.append('password', formData.password || 'password123'); // Default for SMS flow in demo

        const res = await fetch('http://localhost:8000/auth/login', {
          method: 'POST',
          body: formDataLogin,
        });

        if (!res.ok) throw new Error('Ошибка входа');
        const data = await res.json();
        
        login(data.access_token, data.role);
        navigate(`/${data.role}`);
      } catch (err) {
        alert(err.message);
      }
    } else {
      console.log('Registration not fully implemented in demo backend');
      alert('Регистрация успешно отправлена на модерацию!');
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
    </div>
  );

  const renderLoginForm = () => (
    <form onSubmit={handleSubmit} className="auth-form">
      <h2>Вход: {role === 'shop' ? 'Магазин' : role === 'volunteer' ? 'Волонтер' : 'Нуждающийся'}</h2>
      
      {role === 'shop' ? (
        <>
          <input type="email" name="email" placeholder="Email" onChange={handleInputChange} required />
          <input type="password" name="password" placeholder="Пароль" onChange={handleInputChange} required />
        </>
      ) : (
        <>
          <input type="tel" name="phone" placeholder="Номер телефона" onChange={handleInputChange} required />
          <p className="hint">Код подтверждения придет в SMS</p>
        </>
      )}

      <button type="submit" className="btn btn-primary">Войти</button>
      <p onClick={() => setIsLogin(false)} className="toggle-auth">Нет аккаунта? Зарегистрироваться</p>
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

  return (
    <div className="auth-container">
      <div className="auth-card">
        {renderRoleSelection()}
        {isLogin ? renderLoginForm() : (
          role === 'shop' ? renderShopReg() :
          role === 'volunteer' ? renderVolunteerReg() :
          renderNeedyReg()
        )}
      </div>
    </div>
  );
};

export default AuthPage;
