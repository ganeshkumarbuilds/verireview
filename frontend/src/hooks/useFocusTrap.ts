import { useEffect, useRef } from 'react';
import type { RefObject } from 'react';

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * Keeps keyboard focus inside an open dialog, closes it on Escape, locks the
 * page scroll, and restores focus to the previously focused element when the
 * dialog closes. The callback is kept in a ref so a parent re-render cannot
 * re-run the effect and steal focus.
 */
export function useFocusTrap<T extends HTMLElement>(
  active: boolean,
  onEscape: () => void,
  initialFocus?: RefObject<HTMLElement | null>,
) {
  const containerRef = useRef<T | null>(null);
  const escapeRef = useRef(onEscape);
  escapeRef.current = onEscape;
  const initialRef = useRef(initialFocus);
  initialRef.current = initialFocus;
  /** Focus only for a newly mounted surface. A parent trap that is merely
   *  suspended while a nested dialog is open keeps its DOM node and must not
   *  steal focus back when the nested dialog closes. */
  const lastContainer = useRef<T | null>(null);

  useEffect(() => {
    if (!active) return undefined;
    const container = containerRef.current;
    const previous =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;

    const focusable = (): HTMLElement[] => {
      if (!container) return [];
      return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
    };

    if (container !== lastContainer.current) {
      const initial = initialRef.current?.current ?? focusable()[0] ?? container;
      initial?.focus();
      lastContainer.current = container;
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        escapeRef.current();
        return;
      }
      if (event.key !== 'Tab' || !container) return;

      const items = focusable();
      if (items.length === 0) {
        event.preventDefault();
        container.focus();
        return;
      }

      const first = items[0];
      const last = items[items.length - 1];
      const current = document.activeElement;
      const outside = current === null || !container.contains(current);

      if (event.shiftKey && (current === first || outside)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (current === last || outside)) {
        event.preventDefault();
        first.focus();
      }
    };

    // The app scrolls the <main> element, not <body>, so lock every scroll
    // container between the dialog and the document.
    const lockTargets: HTMLElement[] = [];
    if (document.body) lockTargets.push(document.body);
    let ancestor = container?.parentElement ?? null;
    while (ancestor) {
      const overflowY = window.getComputedStyle(ancestor).overflowY;
      if (overflowY === 'auto' || overflowY === 'scroll') {
        lockTargets.push(ancestor);
      }
      ancestor = ancestor.parentElement;
    }
    const previousOverflow = lockTargets.map((element) => element.style.overflow);
    lockTargets.forEach((element) => {
      element.style.overflow = 'hidden';
    });
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      lockTargets.forEach((element, index) => {
        element.style.overflow = previousOverflow[index];
      });
      previous?.focus?.();
    };
  }, [active]);

  return containerRef;
}
