import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { Card } from '../components/ui';

export function HistoryPage() {
  useDocumentTitle('History');
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold text-slate-900">History</h1>
      <Card title="No history yet">
        Review history, verification runs, and per-test evidence arrive in a
        later phase. Every entry will cite the evidence behind its verdict.
      </Card>
    </div>
  );
}
