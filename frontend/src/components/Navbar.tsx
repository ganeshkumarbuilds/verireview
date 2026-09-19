interface NavbarProps {
  onMenuToggle: () => void;
}

export function Navbar({ onMenuToggle }: NavbarProps) {
  return (
    <header className="flex h-14 shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-4">
      <button
        type="button"
        onClick={onMenuToggle}
        aria-label="Toggle navigation"
        className="rounded p-1.5 text-slate-600 hover:bg-slate-100 md:hidden"
      >
        ☰
      </button>
      <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-emerald-600 font-mono text-sm font-bold text-white">
        VR
      </span>
      <div className="leading-tight">
        <p className="text-sm font-semibold tracking-wide text-slate-900">VeriReview</p>
        <p className="hidden text-[11px] text-slate-500 sm:block">
          Generate. Review. Fix. Verify.
        </p>
      </div>
      <span className="ml-auto rounded-full border border-slate-200 bg-slate-50 px-2.5 py-0.5 text-[11px] text-slate-500">
        workspace
      </span>
    </header>
  );
}
