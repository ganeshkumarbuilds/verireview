import { useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

const NAV_LINKS = [
  { to: '/dashboard', label: 'Dashboard', end: true },
  { to: '/projects', label: 'Projects', end: false },
  { to: '/review', label: 'Review', end: false },
  { to: '/history', label: 'History', end: false },
];

function desktopLinkClass({ isActive }: { isActive: boolean }): string {
  return `rounded-lg px-3 py-2 text-sm font-bold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 ${
    isActive
      ? 'bg-indigo-100 text-indigo-800 shadow-sm shadow-indigo-100'
      : 'text-slate-600 hover:bg-indigo-50 hover:text-indigo-900'
  }`;
}

const mobileLinkClass = ({ isActive }: { isActive: boolean }): string =>
  `block rounded-lg px-3 py-2 text-sm font-bold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 ${
    isActive
      ? 'bg-indigo-100 text-indigo-800'
      : 'text-slate-600 hover:bg-indigo-50 hover:text-indigo-900'
  }`;

/** Top navigation bar: brand, bold workspace links, and profile/logout. */
export function Navbar() {
  const { token, user, logout } = useAuth();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);

  const displayName = user?.displayName ?? user?.email ?? 'Account';
  const initials = displayName.slice(0, 2).toUpperCase();

  const handleLogout = async () => {
    await logout();
    setMenuOpen(false);
    setProfileOpen(false);
    navigate('/');
  };

  return (
    <header className="sticky top-0 z-30 shrink-0 border-b border-indigo-100 bg-white/95 shadow-sm shadow-indigo-100/50 backdrop-blur">
      <div className="flex h-16 items-center gap-3 px-4 sm:px-6">
        <button
          type="button"
          onClick={() => setMenuOpen((open) => !open)}
          aria-label="Toggle navigation"
          aria-expanded={menuOpen}
          className="rounded-lg p-2 text-indigo-700 transition-colors hover:bg-indigo-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 md:hidden"
        >
          ☰
        </button>
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-indigo-600 font-mono text-sm font-bold text-white shadow-sm shadow-indigo-200">
          VR
        </span>
        <div className="leading-tight">
          <p className="text-sm font-bold tracking-wide text-indigo-950">VeriReview</p>
          <p className="hidden text-[11px] font-medium text-indigo-500 sm:block">
            Generate. Review. Fix. Verify.
          </p>
        </div>
        <nav
          aria-label="Primary"
          className="absolute left-1/2 hidden -translate-x-1/2 items-center gap-1 md:flex"
        >
          {NAV_LINKS.map((link) => (
            <NavLink key={link.to} to={link.to} end={link.end} className={desktopLinkClass}>
              {link.label}
            </NavLink>
          ))}
        </nav>
        <div className="ml-auto hidden items-center gap-2 md:flex">
          {token ? (
            <>
              <div
                className="relative"
                onKeyDown={(event) => {
                  if (event.key === 'Escape') {
                    setProfileOpen(false);
                  }
                }}
              >
                <button
                  type="button"
                  onClick={() => setProfileOpen((open) => !open)}
                  aria-label="Profile"
                  aria-haspopup="menu"
                  aria-expanded={profileOpen}
                  className="flex max-w-44 items-center gap-2 rounded-full border border-indigo-100 bg-indigo-50/60 py-1 pl-1 pr-3 transition-colors hover:bg-indigo-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
                >
                  <span
                    aria-hidden="true"
                    className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-indigo-600 text-[11px] font-bold text-white"
                  >
                    {initials}
                  </span>
                  <span className="truncate text-sm font-bold text-indigo-950">{displayName}</span>
                </button>
                {profileOpen && (
                  <div
                    role="menu"
                    aria-label="Profile"
                    className="absolute right-0 mt-2 w-60 rounded-xl border border-indigo-100 bg-white p-2 shadow-lg shadow-indigo-100"
                  >
                    <p className="px-3 pt-2 text-[11px] font-semibold uppercase tracking-wider text-slate-400">
                      Signed in as
                    </p>
                    <p className="truncate px-3 pb-2 pt-0.5 text-sm font-bold text-indigo-950">
                      {user?.email ?? displayName}
                    </p>
                    {user?.displayName && user?.email && (
                      <p className="truncate px-3 pb-2 text-xs text-slate-500">
                        {user.displayName}
                      </p>
                    )}
                  </div>
                )}
              </div>
              <button
                type="button"
                onClick={() => void handleLogout()}
                className="rounded-lg border border-indigo-200 bg-white px-4 py-2 text-sm font-bold text-indigo-700 shadow-sm transition-colors hover:bg-indigo-50 hover:border-indigo-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
              >
                Logout
              </button>
            </>
          ) : (
            <>
              <NavLink
                to="/login"
                className="rounded-lg px-3 py-2 text-sm font-bold text-slate-600 transition-colors hover:bg-indigo-50 hover:text-indigo-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
              >
                Login
              </NavLink>
              <NavLink
                to="/register"
                className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-bold text-white shadow-sm shadow-indigo-200 transition-colors hover:bg-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
              >
                Register
              </NavLink>
            </>
          )}
        </div>
      </div>
      {menuOpen && (
        <nav
          aria-label="Mobile"
          className="border-t border-indigo-100 bg-white px-4 py-2 md:hidden"
        >
          {NAV_LINKS.map((link) => (
            <NavLink
              key={link.to}
              to={link.to}
              end={link.end}
              onClick={() => setMenuOpen(false)}
              className={mobileLinkClass}
            >
              {link.label}
            </NavLink>
          ))}
          <div className="mt-1 border-t border-indigo-100 py-2">
            {token ? (
              <div className="space-y-2">
                <p className="truncate px-3 text-sm text-slate-600">
                  <span className="font-bold text-indigo-950">{displayName}</span>
                  {user?.email && user.email !== displayName ? ` · ${user.email}` : ''}
                </p>
                <button
                  type="button"
                  onClick={() => void handleLogout()}
                  className="w-full rounded-lg border border-indigo-200 bg-white px-3 py-2 text-center text-sm font-bold text-indigo-700 transition-colors hover:bg-indigo-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
                >
                  Logout
                </button>
              </div>
            ) : (
              <div className="flex gap-2">
                <NavLink
                  to="/login"
                  onClick={() => setMenuOpen(false)}
                  className="flex-1 rounded-lg border border-indigo-200 px-3 py-2 text-center text-sm font-bold text-indigo-700"
                >
                  Login
                </NavLink>
                <NavLink
                  to="/register"
                  onClick={() => setMenuOpen(false)}
                  className="flex-1 rounded-lg bg-indigo-600 px-3 py-2 text-center text-sm font-bold text-white"
                >
                  Register
                </NavLink>
              </div>
            )}
          </div>
        </nav>
      )}
    </header>
  );
}
