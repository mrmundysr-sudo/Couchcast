import XCTest
@testable import CouchcastCore

final class InstallTests: XCTestCase {
    let pkg = ReceiverPackage(zip: Data(), title: "Couchcast Receiver", version: [1, 0, 2])

    func testDigestMatchesRfc2617Example() {
        let ch = DigestAuth.Challenge(realm: "testrealm@host.com", nonce: "dcd98b7102dd2f0e8b11d0f600bfb0c093", qop: "auth", opaque: "5ccc069c403ebaf9f0171e9517f40e41")
        XCTAssertEqual(DigestAuth.response(user: "Mufasa", password: "Circle Of Life", method: "GET", uri: "/dir/index.html", challenge: ch, nc: "00000001", cnonce: "0a4f113b"),
                       "6629fae49393a05397450978507c4ef1")
        let h = DigestAuth.authorization(user: "Mufasa", password: "Circle Of Life", method: "GET", uri: "/dir/index.html", challenge: ch, nc: "00000001", cnonce: "0a4f113b")
        XCTAssertTrue(h.contains("response=\"6629fae49393a05397450978507c4ef1\"") && h.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"") && h.hasPrefix("Digest username=\"Mufasa\""))
    }

    func testParsesChallenge() {
        let c = DigestAuth.parseChallenge("Digest realm=\"rokudev\", qop=\"auth\", nonce=\"abc123\", opaque=\"zz\"")
        XCTAssertEqual(c, DigestAuth.Challenge(realm: "rokudev", nonce: "abc123", qop: "auth", opaque: "zz"))
        XCTAssertNil(DigestAuth.parseChallenge("Basic realm=\"x\""))
        XCTAssertNil(DigestAuth.parseChallenge(nil))
    }

    func testVersionsAndPackageInfo() {
        XCTAssertEqual(ReceiverPackage.parseVersion("1.0.00001"), [1, 0, 1])
        XCTAssertNil(ReceiverPackage.parseVersion("abc"))
        XCTAssertGreaterThan(ReceiverPackage.compare([1, 0, 2], [1, 0, 1]), 0)
        XCTAssertEqual(ReceiverPackage.compare([1, 0], [1, 0, 0]), 0)
        let p = ReceiverPackage(zip: Data([1]), infoJSON: Data("{\"title\":\"Couchcast Receiver\",\"version\":[1,0,5]}".utf8))
        XCTAssertEqual(p?.versionName, "1.0.5"); XCTAssertEqual(p?.title, "Couchcast Receiver")
        XCTAssertNil(ReceiverPackage(zip: Data(), infoJSON: Data("{}".utf8)))
    }

    func testClassifiesInstalledReceiver() {
        XCTAssertEqual(InstallPlanner.classify(nil, pkg), .unknown)
        XCTAssertEqual(InstallPlanner.classify([RokuApp(id: "12", name: "Netflix")], pkg), .none)
        XCTAssertEqual(InstallPlanner.classify([RokuApp(id: "dev", name: "My Test App", version: "1.0.1")], pkg), .otherDevApp)
        XCTAssertEqual(InstallPlanner.classify([RokuApp(id: "dev", name: "Couchcast Receiver", version: "1.0.00001")], pkg), .outdated)
        XCTAssertEqual(InstallPlanner.classify([RokuApp(id: "dev", name: "Couchcast Receiver", version: "1.0.00002")], pkg), .current)
        // an app named like ours that is not the dev channel must never count
        XCTAssertEqual(InstallPlanner.classify([RokuApp(id: "999", name: "Couchcast Receiver", version: "1.0.2")], pkg), .none)
    }

    func testPlannerNeverSkipsDeveloperMode() {
        XCTAssertEqual(InstallPlanner.decide(.devModeOff, restartPending: false, receiver: .unknown), .devMode)
        XCTAssertEqual(InstallPlanner.decide(.devModeOff, restartPending: true, receiver: .unknown), .restart)
        XCTAssertEqual(InstallPlanner.decide(.rokuUnreachable, restartPending: true, receiver: .unknown), .restart)
        XCTAssertEqual(InstallPlanner.decide(.rokuUnreachable, restartPending: false, receiver: .unknown), .problem)
        XCTAssertEqual(InstallPlanner.decide(.networkDown, restartPending: true, receiver: .none), .problem)
        XCTAssertEqual(InstallPlanner.decide(.ecpBlocked, restartPending: false, receiver: .none), .problem)
        XCTAssertEqual(InstallPlanner.decide(.installerReady, restartPending: false, receiver: .current), .done)
        XCTAssertEqual(InstallPlanner.decide(.installerReady, restartPending: false, receiver: .outdated), .credentials)
    }

    func testClassifiesInstallerReplies() {
        func p(_ c: Int, _ b: String) -> InstallOutcome { RokuDevClient.parseResponse(code: c, html: b) }
        XCTAssertEqual(p(401, ""), .badPassword)
        XCTAssertEqual(p(200, "<font color=\"red\">Install Success.</font>"), .accepted(identical: false))
        XCTAssertEqual(p(200, "<font color=\"red\">Identical to previous version -- not replacing.</font>"), .accepted(identical: true))
        if case .compileFailed = p(200, "<font color=\"red\">Install Failure: Compilation Failed.</font>") {} else { XCTFail("compile") }
        if case .rejected = p(200, "<font color=\"red\">Install Failure: Some Other Thing</font>") {} else { XCTFail("rejected") }
        // 200 without a clear message must NOT be treated as success
        if case .unknown = p(200, "<html>hello</html>") {} else { XCTFail("unknown") }
        if case .rejected = p(500, "") {} else { XCTFail("500") }
    }

    func testMultipartIsWrittenLikeCurl() {
        let body = RokuDevClient.multipartBody(zip: Data([0x50, 0x4b, 3, 4]), boundary: "B")
        let text = String(decoding: body, as: UTF8.self)
        XCTAssertTrue(text.hasPrefix("--B\r\nContent-Disposition: form-data; name=\"mysubmit\"\r\n\r\nReplace\r\n--B\r\n"))
        XCTAssertTrue(text.contains("name=\"archive\"; filename=\"couchcast-receiver.zip\"\r\nContent-Type: application/zip\r\n\r\n"))
        XCTAssertTrue(text.hasSuffix("\r\n--B--\r\n"))
        XCTAssertFalse(text.contains("Content-Length"))   // a per-part Content-Length makes the Roku say "mysubmit Field Not Found"
    }
}
