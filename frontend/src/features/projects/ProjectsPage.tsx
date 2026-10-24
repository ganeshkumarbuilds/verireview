import { Link } from 'react-router-dom';
import { FeaturePage } from '../../components/FeaturePage';
import {
  Badge,
  Card,
  EmptyState,
  ErrorAlert,
  LoadingState,
  primaryButtonClass,
  quietButtonClass,
  secondaryButtonClass,
} from '../../components/ui';
import { formatRelativeTime } from '../../components/workflow';
import { useProjectRows } from './hooks/useProjectRows';

function statusTone(status: string): 'blue' | 'green' | 'red' | 'gray' {
  switch (status) {
    case 'COMPLETED':
    case 'ACTIVE':
      return 'green';
    case 'FAILED':
      return 'red';
    case 'RUNNING':
    case 'QUEUED':
      return 'blue';
    default:
      return 'gray';
  }
}

/**
 * The project list and nothing else. Every action opens the matching feature
 * page with this project already selected; creation stays on /review and
 * /generate.
 */
export function ProjectsPage() {
  const { rows, loading, error, reload } = useProjectRows();

  return (
    <FeaturePage
      title="Projects"
      description="Every project you own, with its latest state. Open Review, Fix or Verify to work on one."
      steps={['Your projects']}
      current={0}
      actions={
        <Link to="/review" className={primaryButtonClass}>
          Add code
        </Link>
      }
    >
      {error && <ErrorAlert message={error} onRetry={() => void reload()} />}

      <Card
        title={`All projects (${rows.length})`}
        subtitle="Source, last activity and the latest review state come straight from the backend."
      >
        {loading && rows.length === 0 ? (
          <LoadingState label="Loading projects…" />
        ) : error && rows.length === 0 ? (
          <p className="py-4 text-sm text-slate-600">
            Projects could not be loaded. Fix the error above and retry.
          </p>
        ) : rows.length === 0 ? (
          <EmptyState
            title="No projects yet."
            body="Start in Review to upload a ZIP, import a GitHub repository or paste code. Or generate a new project."
            action={
              <div className="flex flex-wrap justify-center gap-2">
                <Link to="/review" className={primaryButtonClass}>
                  Add code in Review
                </Link>
                <Link to="/generate" className={secondaryButtonClass}>
                  Generate a project
                </Link>
              </div>
            }
          />
        ) : (
          <>
            <div className="hidden overflow-x-auto lg:block">
              <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-200 text-left text-xs font-semibold uppercase tracking-wider text-slate-500">
                  <th scope="col" className="px-3 py-3">Project</th>
                  <th scope="col" className="px-3 py-3">Source</th>
                  <th scope="col" className="px-3 py-3">Last activity</th>
                  <th scope="col" className="px-3 py-3">Status</th>
                  <th scope="col" className="px-3 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {rows.map(({ project, status, lastActivityAt }) => (
                  <tr key={project.id} className="hover:bg-slate-50">
                    <td className="max-w-[18rem] px-3 py-3">
                      <p className="truncate font-semibold text-slate-900">{project.name}</p>
                      <p className="truncate text-xs text-slate-500">
                        {project.language ?? 'unknown language'} ·{' '}
                        {project.fileCount.toLocaleString()} files
                      </p>
                    </td>
                    <td className="whitespace-nowrap px-3 py-3 text-slate-600">
                      {project.sourceType}
                    </td>
                    <td className="whitespace-nowrap px-3 py-3 text-slate-600">
                      {formatRelativeTime(lastActivityAt)}
                    </td>
                    <td className="whitespace-nowrap px-3 py-3">
                      <Badge tone={statusTone(status)}>{status}</Badge>
                    </td>
                    <td className="whitespace-nowrap px-3 py-3 text-right">
                      <div className="flex justify-end gap-1">
                        <Link
                          to={`/review?project=${project.id}`}
                          className={quietButtonClass}
                          aria-label={`Review ${project.name}`}
                        >
                          Review
                        </Link>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="space-y-3 lg:hidden">
            {rows.map(({ project, status, lastActivityAt }) => (
              <li key={project.id} className="rounded-xl border border-indigo-100 bg-white p-4">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0 flex-1">
                    <Link
                      to={`/review?project=${project.id}`}
                      className="break-words font-semibold text-slate-900 hover:text-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
                    >
                      {project.name}
                    </Link>
                    <p className="mt-0.5 break-words text-xs text-slate-500">
                      {project.language ?? 'unknown language'} ·{' '}
                      {project.fileCount.toLocaleString()} files · {project.sourceType}
                    </p>
                  </div>
                  <Badge tone={statusTone(status)}>{status}</Badge>
                </div>
                <p className="mt-2 text-xs text-slate-500">
                  Last activity {formatRelativeTime(lastActivityAt)}
                </p>
                <div className="mt-3 flex flex-wrap gap-2">
                  <Link
                    to={`/review?project=${project.id}`}
                    className={quietButtonClass}
                    aria-label={`Review ${project.name}`}
                  >
                    Review
                  </Link>
                </div>
              </li>
            ))}
          </ul>
          </>
        )}
      </Card>
    </FeaturePage>
  );
}
