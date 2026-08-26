import { getFirestore, type Firestore } from 'firebase/firestore'
import { getApps, initializeApp, type FirebaseApp } from 'firebase/app'
import { readFirebaseConfig } from './config'

export function getFirestoreForEnv(env: ImportMetaEnv): Firestore | null {
  const config = readFirebaseConfig(env)
  if (!config) return null
  const app: FirebaseApp = getApps().at(0) ?? initializeApp(config)
  try {
    return getFirestore(app)
  } catch {
    return null
  }
}
