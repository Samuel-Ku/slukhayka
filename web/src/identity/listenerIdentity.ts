import type { Credentials } from "./credentials";
import { decodeRecoveryCode } from "./recoveryCodec";
import { getUiLocale } from "../i18n/locale";
import { translate, type UiLocale } from "../i18n/strings";

/**
 * spec-43/T2 — the auth seam of the silent web profile. The pure identity
 * core below speaks only this language; the Firebase SDK is adapted onto it
 * by `src/firebase/authGateway.ts` and stays outside unit tests, exactly
 * like Android's `ListenerIdentity` seam (ADR-0021).
 */
export interface GatewayUser {
  readonly uid: string;
}

export interface AuthGateway {
  /** Sign in with the given `.local` pair; rejects when no account exists yet. */
  signInWithPassword(email: string, password: string): Promise<GatewayUser>;
  /** Create the anonymous session and immediately elevate it to the given pair. */
  createElevatedAccount(email: string, password: string): Promise<GatewayUser>;
  /** The signed-in user of this browsing context, if any. No side effects. */
  currentUser(): GatewayUser | null;
}

export interface ListenerProfile {
  readonly uid: string;
  readonly nickname: string;
}

export interface IdentityDeps {
  /** null => this deployment carries no Firebase config. */
  gateway: AuthGateway | null;
  readPair: () => Credentials | null;
  persistPair: (pair: Credentials) => void;
  generatePair: () => Credentials;
  randomToken: () => string;
}

/**
 * #1687/#1693 — the default pseudonym follows the UI locale through the ONE
 * string table (`listenerFallback`), not a second hardcoded phrase that could
 * drift. A user-chosen nickname is never touched: this function is only the
 * fallback for an identity that has none.
 */
function defaultListenerName(locale: UiLocale): string {
  return translate(locale, "listenerFallback");
}

/** The display pseudonym derived from a uid, in the given UI locale. */
export function nicknameForUid(uid: string, locale: UiLocale): string {
  if (uid.startsWith("local-")) return defaultListenerName(locale);
  let hash = 0;
  for (let i = 0; i < uid.length; i++)
    hash = (hash * 31 + uid.charCodeAt(i)) >>> 0;
  return `${defaultListenerName(locale)}-${String(hash % 10000).padStart(4, "0")}`;
}

/**
 * Is this nickname one of the GENERATED defaults (either locale)? Only those
 * are re-localized; a name the listener chose is data and stays byte-identical.
 */
function isGeneratedNickname(nickname: string, uid: string): boolean {
  return (
    nickname === nicknameForUid(uid, "uk") ||
    nickname === nicknameForUid(uid, "en")
  );
}

/**
 * #1693 — the profile as the CURRENT UI locale renders it. App recomputes this
 * on every locale change, so a language switch no longer leaves the GENERATED
 * fallback pseudonym in the previous language until reload. The uid is the
 * identity; a CUSTOM nickname is returned untouched.
 */
export function localizedProfile(
  profile: ListenerProfile,
  locale: UiLocale
): ListenerProfile {
  if (!isGeneratedNickname(profile.nickname, profile.uid)) return profile;
  return { uid: profile.uid, nickname: nicknameForUid(profile.uid, locale) };
}

function localProfile(randomToken: () => string): ListenerProfile {
  const token = randomToken()
    .replace(/[^a-z0-9]/gi, "")
    .slice(0, 12)
    .toLowerCase();
  const uid = `local-${token || "anon"}`;
  return { uid, nickname: nicknameForUid(uid, getUiLocale()) };
}

/**
 * spec-43/T2 — silent bootstrap, idempotent and never-throwing-outward:
 * the first visit generates the `.local` pair, signs in (elevating a fresh
 * anonymous account when the account does not exist yet), later visits sign
 * back into the SAME uid. Without a Firebase config — or when every auth
 * path fails — it degrades to a `local-…` profile instead of crashing.
 */
export async function ensureProfile(
  deps: IdentityDeps
): Promise<ListenerProfile> {
  if (deps.gateway === null) return localProfile(deps.randomToken);
  try {
    let pair = deps.readPair();
    if (pair === null) {
      pair = deps.generatePair();
      deps.persistPair(pair);
    }
    let user: GatewayUser;
    try {
      user = await deps.gateway.signInWithPassword(pair.email, pair.password);
    } catch {
      user = await deps.gateway.createElevatedAccount(
        pair.email,
        pair.password
      );
    }
    return { uid: user.uid, nickname: nicknameForUid(user.uid, getUiLocale()) };
  } catch {
    return localProfile(deps.randomToken);
  }
}

/** Read-only view of who is signed in right now; never signs in or creates anything. */
export function currentProfile(
  gateway: AuthGateway | null
): ListenerProfile | null {
  const user = gateway?.currentUser() ?? null;
  return user === null
    ? null
    : { uid: user.uid, nickname: nicknameForUid(user.uid, getUiLocale()) };
}

/**
 * spec-43/T7 — restores the listener's profile from a Recovery Code
 * (SLK1.…). The decoded pair is persisted and signed in; on any failure
 * (bad code, network, wrong password) it returns null and leaves the
 * current session untouched — the same degrade-never rule as ensureProfile.
 */
export async function restoreFromCode(
  gateway: AuthGateway | null,
  code: string,
  persistPair: (pair: Credentials) => void
): Promise<ListenerProfile | null> {
  if (gateway === null) return null;
  const decoded = decodeRecoveryCode(code.trim());
  if (!decoded) return null;
  try {
    const user = await gateway.signInWithPassword(
      decoded.email,
      decoded.password
    );
    persistPair(decoded);
    return { uid: user.uid, nickname: nicknameForUid(user.uid, getUiLocale()) };
  } catch {
    return null;
  }
}
