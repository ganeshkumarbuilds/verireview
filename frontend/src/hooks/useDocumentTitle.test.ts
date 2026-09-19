import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useDocumentTitle } from './useDocumentTitle';

describe('useDocumentTitle', () => {
  it('sets and restores the document title', () => {
    const previous = document.title;
    const { unmount } = renderHook(() => useDocumentTitle('Dashboard'));
    expect(document.title).toBe('Dashboard — VeriReview');
    unmount();
    expect(document.title).toBe(previous);
  });
});
