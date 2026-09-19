import { useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Navbar } from './Navbar';
import { Sidebar } from './Sidebar';

/** Shell layout: navbar + sidebar + main content. Responsive: the sidebar
 *  collapses behind a hamburger toggle below the md breakpoint. */
export function Layout() {
  const [sidebarOpen, setSidebarOpen] = useState(false);

  return (
    <div className="flex h-full flex-col bg-slate-50 text-slate-900">
      <Navbar onMenuToggle={() => setSidebarOpen((open) => !open)} />
      <div className="flex min-h-0 flex-1">
        <Sidebar open={sidebarOpen} onNavigate={() => setSidebarOpen(false)} />
        <main className="min-w-0 flex-1 overflow-y-auto p-4 sm:p-6">
          <div className="mx-auto w-full max-w-5xl">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  );
}
