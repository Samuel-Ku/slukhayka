/**
 * spec-43/T7 — порт RecoveryCodec (spec-40 #276): SLK1.<base64url(email:password)>
 * Версійний префікс дозволяє еволюцію формату; ':' не буває в email.
 * Сміття → null, ніколи не виключення.
 */
export const RECOVERY_PREFIX = 'SLK1'
const MAX_EMAIL_LENGTH = 254
const MAX_PASSWORD_LENGTH = 256

export function encodeRecoveryCode(email: string, password: string): string {
  const payload = `${email}:${password}`
  const bytes = new TextEncoder().encode(payload)
  let binary = ''
  for (const b of bytes) binary += String.fromCharCode(b)
  const base64 = btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
  return `${RECOVERY_PREFIX}.${base64}`
}

export function decodeRecoveryCode(code: string): { email: string; password: string } | null {
  if (!code.startsWith(`${RECOVERY_PREFIX}.`)) return null
  const b64 = code.slice(RECOVERY_PREFIX.length + 1)
  if (b64.length === 0) return null
  let padded = b64.replace(/-/g, '+').replace(/_/g, '/')
  while (padded.length % 4 !== 0) padded += '='
  let payload: string
  try {
    const binary = atob(padded)
    const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0))
    payload = new TextDecoder().decode(bytes)
  } catch {
    return null
  }
  const sep = payload.indexOf(':')
  if (sep <= 0 || sep === payload.length - 1) return null
  const email = payload.slice(0, sep)
  const password = payload.slice(sep + 1)
  if (email.length > MAX_EMAIL_LENGTH || password.length > MAX_PASSWORD_LENGTH) return null
  return { email, password }
}
