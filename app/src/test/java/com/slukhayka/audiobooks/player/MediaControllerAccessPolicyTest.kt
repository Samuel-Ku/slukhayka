package com.slukhayka.audiobooks.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaControllerAccessPolicyTest {

    private val ownUid = 10123

    private fun trusted(
        callerUid: Int = 4242,
        packages: List<String> = emptyList(),
        systemPackages: Set<String> = emptySet(),
    ): Boolean = MediaControllerAccessPolicy.isTrusted(
        ownUid = ownUid,
        callerUid = callerUid,
        callerPackages = packages,
        isSystemPackage = { it in systemPackages },
    )

    @Test
    fun ownProcessIsTrusted() {
        assertTrue(trusted(callerUid = ownUid, packages = listOf("com.slukhayka.audiobooks")))
    }

    @Test
    fun knownMediaControllerIsTrusted() {
        assertTrue(trusted(packages = listOf("com.android.systemui")))
        assertTrue(trusted(packages = listOf("com.google.android.projection.gearhead")))
    }

    @Test
    fun systemPackageIsTrustedEvenWhenNameIsOemSpecific() {
        assertTrue(
            trusted(
                packages = listOf("com.oneplus.media"),
                systemPackages = setOf("com.oneplus.media"),
            )
        )
    }

    @Test
    fun unknownThirdPartyIsRejected() {
        assertFalse(trusted(packages = listOf("com.example.malicious")))
    }

    @Test
    fun unknownUidWithoutPackagesIsRejected() {
        assertFalse(trusted(packages = emptyList()))
    }

    @Test
    fun legacyControllerUidIsRejectedByDefault() {
        assertFalse(trusted(callerUid = MediaControllerAccessPolicy.LEGACY_CONTROLLER_UID))
    }

    @Test
    fun legacyControllerUidIsAllowedOnlyForMediaButtonHandshake() {
        assertTrue(
            MediaControllerAccessPolicy.isTrusted(
                ownUid = ownUid,
                callerUid = MediaControllerAccessPolicy.LEGACY_CONTROLLER_UID,
                callerPackages = emptyList(),
                isSystemPackage = { false },
                legacyMediaButtonController = true,
            )
        )
    }

    @Test
    fun sharedUidWithOneSystemPackageIsTrusted() {
        assertTrue(
            trusted(
                packages = listOf("com.example.thirdparty", "com.android.systemui"),
            )
        )
    }
}
