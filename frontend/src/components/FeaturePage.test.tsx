import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { FeaturePage } from './FeaturePage';

function renderPage(current = 0) {
  return render(
    <MemoryRouter>
      <FeaturePage
        title="Review"
        description="Review only."
        steps={['Choose source', 'Run analysis', 'Findings']}
        current={current}
        actions={<button type="button">Start analysis</button>}
      >
        <p>Body</p>
      </FeaturePage>
    </MemoryRouter>,
  );
}

describe('FeaturePage', () => {
  it('renders the back link, header, one primary action and the body', () => {
    renderPage();
    expect(screen.getByRole('link', { name: /Back to Dashboard/i })).toHaveAttribute(
      'href',
      '/dashboard',
    );
    expect(screen.getByRole('heading', { name: 'Review' })).toBeInTheDocument();
    expect(screen.getByText('Review only.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Start analysis' })).toBeInTheDocument();
    expect(screen.getByText('Body')).toBeInTheDocument();
  });

  it('shows only this feature’s steps and marks the current one', () => {
    renderPage(1);
    const steps = screen.getByRole('navigation', { name: 'Feature steps' });
    const items = within(steps)
      .getAllByRole('listitem')
      .map((item) => item.textContent?.replace(/[0-9]/g, '').trim());
    expect(items).toEqual(['Choose source', 'Run analysis', 'Findings']);
    expect(within(steps).getByText('Run analysis')).toHaveAttribute('aria-current', 'step');
    expect(within(steps).getByText('Choose source')).not.toHaveAttribute('aria-current');
  });
});
