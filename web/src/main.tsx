import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { generateCredentials } from './identity/credentials'
import { BrowserCredentialStore } from './identity/credentialStore'
import { ensureProfile, type ListenerProfile } from './identity/listenerIdentity'
import { createAuthGateway } from './firebase/bootstrap'
import './theme.css'

async function bootstrap(): Promise<ListenerProfile> {
  const gateway = await createAuthGateway(import.meta.env)
  const store = new BrowserCredentialStore(window.localStorage)
  // CSPRNG for the generated credential pair — Math.random is not a key source.
  const randomUnit = () => window.crypto.getRandomValues(new Uint32Array(1))[0]! / 2 ** 32
  return ensureProfile({
    gateway,
    readPair: () => store.load(),
    persistPair: (pair) => store.save(pair),
    generatePair: () => generateCredentials(randomUnit),
    randomToken: () => {
      const bytes = window.crypto.getRandomValues(new Uint8Array(9))
      return Array.from(bytes, (b) => b.toString(36)).join('')
    },
  })
}

const root = createRoot(document.getElementById('root')!)
bootstrap().then((profile) => {
  root.render(
    <StrictMode>
      <App profile={profile} />
    </StrictMode>,
  )
})
