import Testing
@testable import AgentCore

@Test func onlyTypableRolesCountAsTextFields() {
    for role in ["AXTextField", "AXTextArea", "AXComboBox"] {
        #expect(TextFocus.isTextRole(role), "\(role)")
    }
    for role in ["AXButton", "AXWebArea", "AXGroup", "AXPopUpButton", "AXStaticText", "AXWindow"] {
        #expect(!TextFocus.isTextRole(role), "\(role)")
    }
    #expect(!TextFocus.isTextRole(nil))
}

@Test func enteringTextFieldIsReportedImmediately() {
    var filter = TextFocusFilter(releaseDelay: 0.5)
    #expect(filter.update(false, now: 0) == false) // status awal selalu dikirim
    #expect(filter.update(false, now: 0.25) == nil)
    #expect(filter.update(true, now: 0.5) == true)
    #expect(filter.update(true, now: 0.75) == nil)
}

@Test func leavingTextFieldWaitsForReleaseDelay() {
    var filter = TextFocusFilter(releaseDelay: 0.5)
    #expect(filter.update(true, now: 0) == true)
    #expect(filter.update(false, now: 1) == nil)
    #expect(filter.update(false, now: 1.25) == nil)
    #expect(filter.update(false, now: 1.5) == false)
    #expect(filter.update(false, now: 1.75) == nil)
}

@Test func movingBetweenTextFieldsDoesNotFlicker() {
    var filter = TextFocusFilter(releaseDelay: 0.5)
    #expect(filter.update(true, now: 0) == true)
    #expect(filter.update(false, now: 0.25) == nil) // sesaat di luar kolom teks
    #expect(filter.update(true, now: 0.5) == nil)
    #expect(filter.update(false, now: 0.75) == nil) // hitungan mulai dari awal
    #expect(filter.update(false, now: 1.0) == nil)
    #expect(filter.update(false, now: 1.25) == false)
}
