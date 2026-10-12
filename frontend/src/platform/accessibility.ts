/** H5 uni-app renders custom elements. Bridge their keyboard and input semantics. */
export function enableKeyboardAccess() {
  // #ifdef H5
  const sync = () => {
    document.querySelectorAll<HTMLElement>('uni-button[role="button"], uni-button[role="switch"]').forEach(button => {
      const disabled = button.hasAttribute('disabled');
      const value = disabled ? '-1' : '0';
      if (button.getAttribute('tabindex') !== value) button.setAttribute('tabindex', value);
      if (button.getAttribute('aria-disabled') !== String(disabled)) button.setAttribute('aria-disabled', String(disabled));
    });
    document.querySelectorAll<HTMLElement>('uni-input[aria-label]').forEach(wrapper => {
      const input = wrapper.querySelector('input');
      const name = wrapper.getAttribute('aria-label');
      if (input && name && input.getAttribute('aria-label') !== name) input.setAttribute('aria-label', name);
    });
  };
  const keyboard = (event: KeyboardEvent) => {
    const target = event.target instanceof HTMLElement ? event.target : null;
    if ((event.key === 'Enter' || event.key === ' ') && !event.repeat) {
      const button = target?.closest<HTMLElement>('uni-button[role="button"], uni-button[role="switch"]');
      if (button) { event.preventDefault(); if (!button.hasAttribute('disabled')) button.click(); }
    }
    if (event.key === 'Tab') {
      const dialog = document.querySelector<HTMLElement>('[role="dialog"]');
      if (!dialog) return;
      const focusable = Array.from(dialog.querySelectorAll<HTMLElement>('input, [tabindex="0"], button, select, textarea')).filter(element => element.getClientRects().length && !element.hasAttribute('disabled'));
      const first = focusable[0], last = focusable.at(-1);
      if (!first || !last) return;
      if (event.shiftKey && (target === first || !dialog.contains(target))) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && (target === last || !dialog.contains(target))) { event.preventDefault(); first.focus(); }
    }
  };
  sync();
  const observer = new MutationObserver(sync);
  observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['disabled', 'aria-label'] });
  document.addEventListener('keydown', keyboard);
  return () => { observer.disconnect(); document.removeEventListener('keydown', keyboard); };
  // #endif
  // #ifndef H5
  return () => {};
  // #endif
}
