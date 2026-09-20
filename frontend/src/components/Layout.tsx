import { Outlet } from 'react-router-dom';
import { Navbar } from './Navbar';

/** Shell layout: top navbar + main content. Navigation lives in the
 *  navbar on all breakpoints (hamburger menu below md). */
export function Layout() {
  return (
    <div className="flex h-full flex-col bg-indigo-50/60 text-slate-900">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-lg focus:bg-indigo-600 focus:px-4 focus:py-2 focus:text-sm focus:font-semibold focus:text-white"
      >
        Skip to main content
      </a>
      <Navbar />
      <main id="main-content" className="min-w-0 flex-1 overflow-y-auto p-4 sm:p-6 lg:p-8">
        <div className="mx-auto w-full max-w-6xl">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
