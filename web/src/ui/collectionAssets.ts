/**
 * W3.1 — the web twin of Android's `CollectionAssets`: the curated
 * collection assets in display order. Adding a new collection later is one
 * new JSON file in this directory — no code change (the asset loader here is
 * the only seam). Loading is best-effort: a malformed asset contributes no
 * collection (decode returns null); the app never crashes on a bad list.
 */
import nobel from './collections/nobel.json'
import shevchenko from './collections/shevchenko.json'
import booker from './collections/booker.json'
import { validateCollection, type CollectionList } from './collectionModel'

/** The shipped collections, in display order (same as Android's FILE_NAMES). */
const ASSETS = [nobel, shevchenko, booker] as const

/** Decodes every shipped asset; malformed ones contribute nothing. */
export function loadCollections(): CollectionList[] {
  return ASSETS
    .map((asset) => validateCollection(asset))
    .filter((list): list is CollectionList => list !== null)
}