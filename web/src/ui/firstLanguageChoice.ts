/**
 * spec-51 (#742) — the web First Language Choice: a one-time question asked
 * after the first successful catalog sync that brought real content. It
 * mirrors Android's `FirstLanguageChoiceEngine` (same fires-once discipline):
 *
 *  - shown only when the listener has NOT already answered, has NOT already
 *    narrowed the languages away from «Усі», and the catalog really holds at
 *    least one known rendition;
 *  - ANY answer (an explicit selection or a quick action) is terminal and
 *    persisted, so the question never returns across restarts;
 *  - an already-narrowed preference counts as the answer.
 *
 * Pure over injected storage so the fires-once branches are unit-testable.
 */
import type { StorageLike } from './contentLanguagePrefs'

const ANSWERED_KEY = 'slukhayka.first_language_choice.answered'

function defaultStorage(): StorageLike {
  return {
    getItem: (key) => (typeof localStorage === 'undefined' ? null : localStorage.getItem(key)),
    setItem: (key, value) => {
      if (typeof localStorage !== 'undefined') localStorage.setItem(key, value)
    },
  }
}

/** Whether the one-time First Language Choice was answered (either branch). */
export function isFirstLanguageChoiceAnswered(storage: StorageLike = defaultStorage()): boolean {
  try {
    return storage.getItem(ANSWERED_KEY) === 'true'
  } catch {
    return false
  }
}

/** Records that the listener answered, so the question never returns. */
export function markFirstLanguageChoiceAnswered(storage: StorageLike = defaultStorage()): void {
  try {
    storage.setItem(ANSWERED_KEY, 'true')
  } catch {
    // degrade-never: an unwritable store cannot re-ask if it never recorded
  }
}

export interface FirstChoiceInputs {
  /** The loaded catalog holds at least one known content language. */
  hasContent: boolean
  /** The listener already answered (or an active choice counts as the answer). */
  answered: boolean
  /** The persisted content-language selection (empty = «Усі»). */
  selection: readonly string[]
}

/**
 * The question shows only when content exists, the listener has not answered,
 * and the selection is still the default «Усі» (empty). A narrowed selection
 * is an answer already made (US16).
 */
export function shouldAskFirstLanguageChoice({ hasContent, answered, selection }: FirstChoiceInputs): boolean {
  return hasContent && !answered && selection.length === 0
}
