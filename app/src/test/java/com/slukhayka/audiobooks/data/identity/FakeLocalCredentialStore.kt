package com.slukhayka.audiobooks.data.identity

class FakeLocalCredentialStore(
    initial: StoredCredentials? = null
) : LocalCredentialStore {
    private var value: StoredCredentials? = initial

    override fun load(): StoredCredentials? = value

    override fun save(credentials: StoredCredentials) {
        value = credentials
    }

    override fun clear() {
        value = null
    }
}
