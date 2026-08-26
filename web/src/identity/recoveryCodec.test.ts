import { describe, expect, it } from 'vitest'
import { decodeRecoveryCode, encodeRecoveryCode } from './recoveryCodec'

describe('RecoveryCodec SLK1', () => {
  it('round-trips a synthetic .local pair', () => {
    const code = encodeRecoveryCode('x7k2p9q1a8b3c4d5e6f7@slukhayka.local', 'p4ssw0rd-with-symbols-!@#')
    expect(decodeRecoveryCode(code)).toEqual({ email: 'x7k2p9q1a8b3c4d5e6f7@slukhayka.local', password: 'p4ssw0rd-with-symbols-!@#' })
  })

  it('rejects garbage and foreign prefix', () => {
    expect(decodeRecoveryCode('')).toBeNull()
    expect(decodeRecoveryCode('SLK2.abc')).toBeNull()
    expect(decodeRecoveryCode('SLK1.!!!not-base64!!!')).toBeNull()
    expect(decodeRecoveryCode('SLK1.')).toBeNull()
  })
})
