import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ConfirmFixDialog } from './ConfirmFixDialog';

const findings = [
  {
    id: 'f1',
    title: 'SQL string concat',
    severity: 'HIGH',
    filePath: 'Dao.java',
    lineStart: 41,
  },
  {
    id: 'f2',
    title: 'Weak hash',
    severity: 'MEDIUM',
    filePath: 'Hash.java',
    lineStart: 7,
  },
];

describe('ConfirmFixDialog', () => {
  it('renders nothing while closed', () => {
    const { container } = render(
      <ConfirmFixDialog
        open={false}
        findings={findings}
        files={['Dao.java']}
        onCancel={vi.fn()}
        onConfirm={vi.fn()}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('lists exactly what will change before the user approves', () => {
    render(
      <ConfirmFixDialog
        open
        findings={findings}
        files={['Dao.java', 'Hash.java']}
        onCancel={vi.fn()}
        onConfirm={vi.fn()}
      />,
    );
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('Fix 2 selected findings?');
    expect(dialog).toHaveTextContent('Dao.java');
    expect(dialog).toHaveTextContent('Hash.java');
    expect(dialog).toHaveTextContent('SQL string concat');
    expect(dialog).toHaveTextContent('Weak hash');
  });

  it('only calls onConfirm when the user confirms', () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(
      <ConfirmFixDialog
        open
        findings={findings}
        files={['Dao.java']}
        onCancel={onCancel}
        onConfirm={onConfirm}
      />,
    );
    expect(onConfirm).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm and fix (2)' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('cancels without confirming', () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(
      <ConfirmFixDialog
        open
        findings={findings}
        files={['Dao.java']}
        onCancel={onCancel}
        onConfirm={onConfirm}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('closes on Escape', () => {
    const onCancel = vi.fn();
    render(
      <ConfirmFixDialog
        open
        findings={findings}
        files={['Dao.java']}
        onCancel={onCancel}
        onConfirm={vi.fn()}
      />,
    );
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('surfaces a backend rejection as a plain message', () => {
    render(
      <ConfirmFixDialog
        open
        findings={findings}
        files={['Dao.java']}
        error="An approved fix request is required before a fix run can start."
        onCancel={vi.fn()}
        onConfirm={vi.fn()}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent(
      'An approved fix request is required before a fix run can start.',
    );
  });
});
