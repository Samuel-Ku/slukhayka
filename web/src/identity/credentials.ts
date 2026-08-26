export interface Credentials {
  readonly email: string
  readonly password: string
}

const EMAIL_ALPHABET = 'abcdefghkmnpqrstuvwxyz23456789'
const PASSWORD_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghkmnpqrstuvwxyz23456789!@#$%'

function pick(alphabet: string, random: () => number, length: number): string {
  let out = ''
  for (let i = 0; i < length; i++) {
    out += alphabet[Math.floor(random() * alphabet.length)]
  }
  return out
}

/**
 * spec-43/T2 — the generated `.local` credential pair of the silent web
 * profile, mirroring Android's generated identity (ADR-0021): a synthetic
 * address that delivers nowhere plus a random password. Pure: the random
 * source is injected, so tests pin exact outputs.
 */
export function generateCredentials(randomBytes: () => number): Credentials {
  return {
    email: `${pick(EMAIL_ALPHABET, randomBytes, 12)}@slukhayka.local`,
    password: pick(PASSWORD_ALPHABET, randomBytes, 24),
  }
}

export function isValidCredentialPair(pair: Credentials | null): boolean {
  return (
    pair !== null &&
    /^[a-z0-9]{4,64}@slukhayka\.local$/.test(pair.email) &&
    pair.password.length >= 12
  )
}
