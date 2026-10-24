import { Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { RedirectIfAuthenticated, RequireAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { DashboardPage } from './pages/DashboardPage';
import { HistoryPage } from './pages/HistoryPage';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { RegisterPage } from './pages/RegisterPage';
import { GeneratePage } from './features/generate/GeneratePage';
import { ReviewPage } from './features/review/ReviewPage';
import { FindingDetailPage } from './features/review/FindingDetailPage';
import { ProjectsPage } from './features/projects/ProjectsPage';

/** Old /projects/:id workspace bookmarks now open Review for that project. */
function LegacyProjectRedirect() {
  const { id } = useParams<{ id: string }>();
  return (
    <Navigate
      to={id ? `/review?project=${encodeURIComponent(id)}` : '/projects'}
      replace
    />
  );
}

/** Old /fix and /verify bookmarks now open the Review flow (project kept). */
function LegacyFlowRedirect() {
  const location = useLocation();
  const project = new URLSearchParams(location.search).get('project');
  return (
    <Navigate
      to={project ? `/review?project=${encodeURIComponent(project)}` : '/review'}
      replace
    />
  );
}

/**
 * One route per feature. Dashboard cards, the navbar and the project list all
 * point at these pages, and every page owns exactly one workflow. The finding
 * detail route stays under /projects so existing deep links keep working.
 */
export function AppRoutes() {
  return (
    <Routes>
      <Route index element={<HomePage />} />
      <Route
        path="login"
        element={
          <RedirectIfAuthenticated>
            <LoginPage />
          </RedirectIfAuthenticated>
        }
      />
      <Route
        path="register"
        element={
          <RedirectIfAuthenticated>
            <RegisterPage />
          </RedirectIfAuthenticated>
        }
      />
      <Route element={<Layout />}>
        <Route
          path="dashboard"
          element={
            <RequireAuth>
              <DashboardPage />
            </RequireAuth>
          }
        />
        <Route
          path="projects"
          element={
            <RequireAuth>
              <ProjectsPage />
            </RequireAuth>
          }
        />
        {/* Bookmark compatibility: the combined project workspace is gone. */}
        <Route
          path="projects/:id"
          element={
            <RequireAuth>
              <LegacyProjectRedirect />
            </RequireAuth>
          }
        />
        <Route
          path="projects/:projectId/reviews/:reviewId/findings/:findingId"
          element={
            <RequireAuth>
              <FindingDetailPage />
            </RequireAuth>
          }
        />
        <Route
          path="generate"
          element={
            <RequireAuth>
              <GeneratePage />
            </RequireAuth>
          }
        />
        <Route
          path="review"
          element={
            <RequireAuth>
              <ReviewPage />
            </RequireAuth>
          }
        />
        <Route
          path="fix"
          element={
            <RequireAuth>
              <LegacyFlowRedirect />
            </RequireAuth>
          }
        />
        <Route
          path="verify"
          element={
            <RequireAuth>
              <LegacyFlowRedirect />
            </RequireAuth>
          }
        />
        <Route
          path="history"
          element={
            <RequireAuth>
              <HistoryPage />
            </RequireAuth>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
