/** Bounds apply to decoded text payloads, never to streaming audiobook audio. */
export const MAX_TEXT_BYTES = 2 * 1024 * 1024
export const TEXT_DEADLINE_MS = 18_000

/** One deadline covers headers, redirects and the complete text body. */
export async function withTextDeadline<T>(operation: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  const expired = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      controller.abort()
      reject(new Error('upstream text timeout'))
    }, TEXT_DEADLINE_MS)
  })
  try {
    return await Promise.race([operation(controller.signal), expired])
  } finally {
    clearTimeout(timer)
  }
}

/** Count actual bytes; absent, compressed or dishonest Content-Length is not a bound. */
export async function readBoundedText(response: Response, signal: AbortSignal): Promise<string> {
  if (Number(response.headers.get('content-length')) > MAX_TEXT_BYTES) {
    void response.body?.cancel().catch(() => {})
    throw new Error('upstream text too large')
  }
  if (!response.body) return ''
  const reader = response.body.getReader()
  const cancel = () => { void reader.cancel().catch(() => {}) }
  signal.addEventListener('abort', cancel, { once: true })
  // Fixed storage also bounds overhead if the peer sends millions of tiny
  // chunks. A growing array of decoded strings would not provide that bound.
  const buffer = new Uint8Array(MAX_TEXT_BYTES)
  let bytes = 0
  try {
    while (true) {
      if (signal.aborted) throw new Error('upstream text timeout')
      const { done, value } = await reader.read()
      if (signal.aborted) throw new Error('upstream text timeout')
      if (done) break
      if (bytes + value.byteLength > MAX_TEXT_BYTES) throw new Error('upstream text too large')
      buffer.set(value, bytes)
      bytes += value.byteLength
    }
    return new TextDecoder().decode(buffer.subarray(0, bytes))
  } catch (error) {
    cancel()
    throw error
  } finally {
    signal.removeEventListener('abort', cancel)
    reader.releaseLock()
  }
}
