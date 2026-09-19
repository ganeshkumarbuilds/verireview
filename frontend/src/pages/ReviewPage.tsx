import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { Badge, Card } from '../components/ui';

export function ReviewPage() {
  useDocumentTitle('Review');
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold text-slate-900">Review</h1>
      <Card title="No review running">
        Triggering reviews, finding lists with severity and status filters, and
        the agent timeline arrive in a later phase.
      </Card>
      <Card title="Severity scale">
        <div className="flex flex-wrap gap-2">
          <Badge tone="red">CRITICAL</Badge>
          <Badge tone="amber">HIGH</Badge>
          <Badge tone="blue">MEDIUM</Badge>
          <Badge tone="gray">LOW</Badge>
          <Badge tone="gray">INFO</Badge>
        </div>
      </Card>
    </div>
  );
}
