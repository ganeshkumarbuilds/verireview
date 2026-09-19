import { Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { DashboardPage } from './pages/DashboardPage';
import { HistoryPage } from './pages/HistoryPage';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { ProjectDetailPage } from './pages/ProjectDetailPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { RegisterPage } from './pages/RegisterPage';
import { ReviewPage } from './pages/ReviewPage';

/** Route table. `/`, `/login`, and `/register` render standalone;
 *  the workspace (dashboard, projects, review, history) lives under Layout.
 *  Project routes require a session; auth wiring is live. */
export function AppRoutes() {
  return (
    <Routes>
      <Route index element={<HomePage />} />
      <Route path="login" element={<LoginPage />} />
      <Route path="register" element={<RegisterPage />} />
      <Route element={<Layout />}>
        <Route path="dashboard" element={<DashboardPage />} />
        <Route
          path="projects"
          element={
            <RequireAuth>
              <ProjectsPage />
            </RequireAuth>
          }
        />
        <Route
          path="projects/:id"
          element={
            <RequireAuth>
              <ProjectDetailPage />
            </RequireAuth>
          }
        />
        <Route path="review" element={<ReviewPage />} />
        <Route path="history" element={<HistoryPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
