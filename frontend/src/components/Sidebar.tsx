import { NavLink } from 'react-router-dom';

const SECTIONS: { title: string; links: { to: string; label: string }[] }[] = [
  {
    title: 'Workspace',
    links: [
      { to: '/dashboard', label: 'Dashboard' },
      { to: '/projects', label: 'Projects' },
      { to: '/review', label: 'Review' },
      { to: '/history', label: 'History' },
    ],
  },
  {
    title: 'Account',
    links: [
      { to: '/login', label: 'Login' },
      { to: '/register', label: 'Register' },
    ],
  },
];

interface SidebarProps {
  open: boolean;
  onNavigate: () => void;
}

export function Sidebar({ open, onNavigate }: SidebarProps) {
  return (
    <aside
      aria-label="Primary"
      className={`${
        open ? 'fixed inset-y-0 left-0 z-40 flex' : 'hidden'
      } w-60 shrink-0 flex-col gap-6 border-r border-slate-200 bg-white p-4 pt-16 md:static md:z-auto md:flex md:pt-4`}
    >
      {SECTIONS.map((section) => (
        <nav key={section.title} aria-label={section.title}>
          <p className="mb-1 px-2 text-[11px] font-semibold uppercase tracking-wider text-slate-400">
            {section.title}
          </p>
          <ul className="space-y-0.5">
            {section.links.map((link) => (
              <li key={link.to}>
                <NavLink
                  to={link.to}
                  end={link.to === '/dashboard'}
                  onClick={onNavigate}
                  className={({ isActive }) =>
                    `block rounded-lg px-2 py-1.5 text-sm ${
                      isActive
                        ? 'bg-indigo-50 font-medium text-indigo-700'
                        : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
                    }`
                  }
                >
                  {link.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      ))}
    </aside>
  );
}
