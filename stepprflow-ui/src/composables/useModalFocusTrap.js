import { watch, nextTick, onBeforeUnmount } from 'vue'

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * Minimal focus-trap + focus-restoration helper for modal dialogs.
 *
 * - When the modal opens: remembers the element that triggered it, then moves
 *   focus to `initialFocusRef` (or the first focusable element in the dialog).
 * - While open: `onTabKey` keeps Tab/Shift+Tab navigation cycling within the
 *   dialog instead of escaping to the rest of the page.
 * - When the modal closes: focus is restored to the triggering element.
 *
 * Escape-to-close is intentionally left to the caller (`@keydown.esc` in the
 * template) since the action to perform on escape differs per component.
 *
 * @param {() => boolean} isOpen - reactive getter for the modal's visibility
 * @param {import('vue').Ref<HTMLElement|null>} dialogRef - ref on the dialog root element
 * @param {import('vue').Ref<HTMLElement|null>} [initialFocusRef] - ref on the element to focus on open
 */
export function useModalFocusTrap(isOpen, dialogRef, initialFocusRef) {
  let triggerEl = null

  function getFocusable() {
    if (!dialogRef.value) return []
    return Array.from(dialogRef.value.querySelectorAll(FOCUSABLE_SELECTOR))
  }

  function onTabKey(event) {
    const focusable = getFocusable()
    if (!focusable.length) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]

    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  watch(isOpen, (open) => {
    if (open) {
      triggerEl = document.activeElement
      nextTick(() => {
        const target = initialFocusRef?.value || getFocusable()[0]
        target?.focus?.()
      })
    } else if (triggerEl) {
      if (typeof triggerEl.focus === 'function' && document.body.contains(triggerEl)) {
        triggerEl.focus()
      }
      triggerEl = null
    }
  })

  onBeforeUnmount(() => {
    triggerEl = null
  })

  return { onTabKey }
}
