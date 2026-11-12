//! Runs the classifier against the fixtures shared with the Kotlin parity test
//! (`app/src/test/java/com/nuvio/tv/core/debrid/ReleaseFactsParityTest.kt`). Expectations are
//! produced by the Kotlin reference implementation.

mod common;

use common::{owned_stream, to_json};
use nuvio_core::release_facts::classify;
use serde_json::Value;

#[test]
fn matches_kotlin_reference_fixtures() {
    let raw = include_str!("fixtures/release_facts.json");
    let fixtures: Vec<Value> = serde_json::from_str(raw).expect("valid fixture JSON");
    assert!(fixtures.len() >= 50, "fixture set unexpectedly small");
    for fixture in &fixtures {
        let name = fixture["name"].as_str().unwrap();
        let expected = fixture
            .get("expected")
            .unwrap_or_else(|| panic!("{name}: missing expectation"));
        let stream = owned_stream(fixture.get("input").unwrap_or(&Value::Null));
        assert_eq!(
            &to_json(&classify(&stream.input())),
            expected,
            "fixture {name}"
        );
    }
}
