import Testing
@testable import AgentCore

@Test func versionDisplayIncludesBuildNumber() {
    #expect(AppVersion(short: "0.1.0", build: "12").display == "0.1.0 (build 12)")
}

@Test func versionFallsBackWhenInfoPlistIsMissing() {
    #expect(AppVersion(infoDictionary: nil).display == "0.0.0 (build 0)")
}

@Test func constantsMatchProtocolDoc() {
    #expect(AgentConstants.protocolVersion == 1)
    #expect(AgentConstants.defaultPort == 47810)
    #expect(AgentConstants.bonjourServiceType == "_armrest._tcp")
}
