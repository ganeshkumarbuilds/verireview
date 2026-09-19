import { useEffect } from 'react';

/** Sets the document title while a page is mounted. */
export function useDocumentTitle(title: string): void {
  useEffect(() => {
    const previous = document.title;
    document.title = `${title} — VeriReview`;
    return () => {
      document.title = previous;
    };
  }, [title]);
}
