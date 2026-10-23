package com.mundymorning.couchcast

import com.mundymorning.couchcast.install.DigestAuth
import com.mundymorning.couchcast.install.InstallOutcome
import com.mundymorning.couchcast.install.InstallPlanner
import com.mundymorning.couchcast.install.ReceiverPackage
import com.mundymorning.couchcast.install.ReceiverState
import com.mundymorning.couchcast.install.Reachability
import com.mundymorning.couchcast.install.RokuDevClient
import com.mundymorning.couchcast.install.Step
import com.mundymorning.couchcast.roku.RokuApp
import com.mundymorning.couchcast.roku.RokuParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstallLogicTest {
    private fun zip(manifest: String): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use {
            it.putNextEntry(ZipEntry("manifest")); it.write(manifest.toByteArray()); it.closeEntry()
        }
        return bo.toByteArray()
    }

    private val pkg = ReceiverPackage(zip("title=Couchcast Receiver\nmajor_version=1\nminor_version=0\nbuild_version=00002\n"))

    @Test
    fun digestMatchesRfc2617Example() {
        val ch = DigestAuth.Challenge("testrealm@host.com", "dcd98b7102dd2f0e8b11d0f600bfb0c093", "auth", "5ccc069c403ebaf9f0171e9517f40e41")
        val r = DigestAuth.response("Mufasa", "Circle Of Life", "GET", "/dir/index.html", ch, "00000001", "0a4f113b")
        assertEquals("6629fae49393a05397450978507c4ef1", r)
    }

    @Test
    fun parsesChallenge() {
        val c = DigestAuth.parseChallenge("Digest realm=\"rokudev\", qop=\"auth\", nonce=\"abc123\", opaque=\"zz\"")
        assertNotNull(c)
        assertEquals("rokudev", c!!.realm); assertEquals("abc123", c.nonce); assertEquals("auth", c.qop)
        assertNull(DigestAuth.parseChallenge("Basic realm=\"x\""))
        assertNull(DigestAuth.parseChallenge(null))
    }

    @Test
    fun readsPackageManifest() {
        assertEquals("Couchcast Receiver", pkg.title)
        assertEquals(listOf(1, 0, 2), pkg.version)
    }

    @Test
    fun versionParsingAndCompare() {
        assertEquals(listOf(1, 0, 1), ReceiverPackage.parseVersion("1.0.00001"))
        assertNull(ReceiverPackage.parseVersion("abc"))
        assertTrue(ReceiverPackage.compare(listOf(1, 0, 2), listOf(1, 0, 1)) > 0)
        assertEquals(0, ReceiverPackage.compare(listOf(1, 0), listOf(1, 0, 0)))
    }

    @Test
    fun classifiesInstalledReceiver() {
        assertEquals(ReceiverState.UNKNOWN, InstallPlanner.classify(null, pkg))
        assertEquals(ReceiverState.NONE, InstallPlanner.classify(listOf(RokuApp("12", "Netflix")), pkg))
        assertEquals(ReceiverState.OTHER_DEV_APP, InstallPlanner.classify(listOf(RokuApp("dev", "My Test App", "1.0.1")), pkg))
        assertEquals(ReceiverState.OUTDATED, InstallPlanner.classify(listOf(RokuApp("dev", "Couchcast Receiver", "1.0.00001")), pkg))
        assertEquals(ReceiverState.CURRENT, InstallPlanner.classify(listOf(RokuApp("dev", "Couchcast Receiver", "1.0.00002")), pkg))
        // an app named like ours that is not the dev channel must never count
        assertEquals(ReceiverState.NONE, InstallPlanner.classify(listOf(RokuApp("999", "Couchcast Receiver", "1.0.2")), pkg))
    }

    @Test
    fun plannerNeverSkipsDeveloperMode() {
        assertEquals(Step.DEV_MODE, InstallPlanner.decide(Reachability.DEV_MODE_OFF, false, ReceiverState.UNKNOWN))
        assertEquals(Step.RESTART, InstallPlanner.decide(Reachability.DEV_MODE_OFF, true, ReceiverState.UNKNOWN))
        assertEquals(Step.RESTART, InstallPlanner.decide(Reachability.ROKU_UNREACHABLE, true, ReceiverState.UNKNOWN))
        assertEquals(Step.PROBLEM, InstallPlanner.decide(Reachability.ROKU_UNREACHABLE, false, ReceiverState.UNKNOWN))
        assertEquals(Step.PROBLEM, InstallPlanner.decide(Reachability.NETWORK_DOWN, true, ReceiverState.NONE))
        assertEquals(Step.PROBLEM, InstallPlanner.decide(Reachability.ECP_BLOCKED, false, ReceiverState.NONE))
        assertEquals(Step.CREDENTIALS, InstallPlanner.decide(Reachability.INSTALLER_READY, false, ReceiverState.NONE))
        assertEquals(Step.CREDENTIALS, InstallPlanner.decide(Reachability.INSTALLER_READY, false, ReceiverState.OUTDATED))
        assertEquals(Step.DONE, InstallPlanner.decide(Reachability.INSTALLER_READY, false, ReceiverState.CURRENT))
    }

    @Test
    fun parsesAppVersion() {
        val apps = RokuParse.apps("<apps><app id=\"dev\" type=\"appl\" version=\"1.0.00002\">Couchcast Receiver</app></apps>")
        assertEquals("1.0.00002", apps.single().version)
    }

    @Test
    fun classifiesInstallerReplies() {
        fun p(code: Int, body: String) = RokuDevClient.parseResponse(code, body)
        assertEquals(InstallOutcome.BadPassword, p(401, ""))
        assertEquals(InstallOutcome.Accepted(false), p(200, "<font color=\"red\">Install Success.</font>"))
        assertEquals(InstallOutcome.Accepted(true), p(200, "<font color=\"red\">Identical to previous version -- not replacing.</font>"))
        assertTrue(p(200, "<font color=\"red\">Install Failure: Compilation Failed.</font>") is InstallOutcome.CompileFailed)
        assertTrue(p(200, "<font color=\"red\">Install Failure: Some Other Thing</font>") is InstallOutcome.Rejected)
        // 200 without a clear message must NOT be treated as success
        assertTrue(p(200, "<html>hello</html>") is InstallOutcome.Unknown)
        assertTrue(p(500, "") is InstallOutcome.Rejected)
    }
}
