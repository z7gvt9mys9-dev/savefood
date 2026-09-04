import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import './App.css';
import { AuthProvider } from './context/AuthContext';
import ProtectedRoute from './components/ProtectedRoute';
import HomePage from './Pages/HomePage';
import Header from './Pages/Header/Header';
import Footer from './Pages/Footer/Footer';
import AuthPage from './Pages/Auth/AuthPage';
import ShopDashboard from './Pages/Shop/ShopDashboard';
import NeedyDashboard from './Pages/Needy/NeedyDashboard';
import VolunteerDashboard from './Pages/Volunteer/VolunteerDashboard';
import AdminPanel from './Pages/Admin/AdminPanel';
import AboutPage from './Pages/AboutPage';
import ImpactPage from './Pages/ImpactPage';
import TermsPage from './Pages/TermsPage';
import PrivacyPage from './Pages/PrivacyPage';
function App() {
  return (
    <AuthProvider>
      <Router>
        <div className="App">
          <Header />
          <main className="content">
            <Routes>
              <Route path="/" element={<HomePage />} />
              <Route path="/about" element={<AboutPage />} />
              <Route path="/impact" element={<ImpactPage />} />
              <Route path="/terms" element={<TermsPage />} />
              <Route path="/privacy" element={<PrivacyPage />} />
              <Route path="/auth" element={<AuthPage />} />
              <Route path="/shop" element={
                <ProtectedRoute allowedRoles={['shop']}>
                  <ShopDashboard />
                </ProtectedRoute>
              } />
              <Route path="/needy" element={
                <ProtectedRoute allowedRoles={['needy']}>
                  <NeedyDashboard />
                </ProtectedRoute>
              } />
              <Route path="/volunteer" element={
                <ProtectedRoute allowedRoles={['volunteer']}>
                  <VolunteerDashboard />
                </ProtectedRoute>
              } />
              <Route path="/admin" element={
                <ProtectedRoute allowedRoles={['admin']}>
                  <AdminPanel />
                </ProtectedRoute>
              } />
            </Routes>
          </main>
          <Footer />
        </div>
      </Router>
    </AuthProvider>
  );
}
export default App;
