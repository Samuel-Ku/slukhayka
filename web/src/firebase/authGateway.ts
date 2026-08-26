import { initializeApp, getApps, type FirebaseApp } from 'firebase/app'
import {
  browserLocalPersistence,
  createUserWithEmailAndPassword,
  getAuth,
  indexedDBLocalPersistence,
  setPersistence,
  signInAnonymously,
  linkWithCredential,
  EmailAuthProvider,
  signInWithCredential,
  type Auth,
} from 'firebase/auth'
import type { AuthGateway, GatewayUser } from '../identity/listenerIdentity'
import type { WebFirebaseConfig } from './config'

function toUser(user: { uid: string }): GatewayUser {
  return { uid: user.uid }
}

/**
 * spec-43/T2 — thin glue between firebase/auth and the pure AuthGateway
 * seam. Untested by unit tests by house convention (same as Android's
 * FirebaseListenerIdentity); the JVM-style fake lives in the tests of the
 * pure core.
 */
export class FirebaseAuthGateway implements AuthGateway {
  private constructor(private readonly auth: Auth) {}

  static async create(config: WebFirebaseConfig): Promise<AuthGateway> {
    const app: FirebaseApp = getApps().at(0) ?? initializeApp(config)
    const auth = getAuth(app)
    try {
      await setPersistence(auth, indexedDBLocalPersistence).catch(() =>
        setPersistence(auth, browserLocalPersistence),
      )
    } catch {
      // persistence best-effort: in-memory still works for the session
    }
    return new FirebaseAuthGateway(auth)
  }

  async signInWithPassword(email: string, password: string): Promise<GatewayUser> {
    const credential = EmailAuthProvider.credential(email, password)
    const result = await signInWithCredential(this.auth, credential)
    return toUser(result.user)
  }

  /**
   * ADR-0021 for web: anonymous accounts are reaped by Firebase, so the
   * fresh anonymous session is immediately elevated to the generated pair.
   */
  async createElevatedAccount(email: string, password: string): Promise<GatewayUser> {
    try {
      const anon = await signInAnonymously(this.auth)
      const elevated = await linkWithCredential(
        anon.user,
        EmailAuthProvider.credential(email, password),
      )
      return toUser(elevated.user)
    } catch {
      // The pair may already own an account (e.g. elevation raced another
      // tab): plain email sign-up is the honest last attempt.
      const created = await createUserWithEmailAndPassword(this.auth, email, password)
      return toUser(created.user)
    }
  }

  currentUser(): GatewayUser | null {
    const user = this.auth.currentUser
    return user === null ? null : toUser(user)
  }
}
