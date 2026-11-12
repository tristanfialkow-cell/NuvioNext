//! Runs the classifier against the fixtures shared with the Kotlin parity test
//! (`app/src/test/java/com/nuvio/tv/core/debrid/ReleaseFactsParityTest.kt`). Expectations are
//! produced by the Kotlin reference implementation.

use nuvio_core::release_facts::{classify, ParsedRelease, ReleaseFacts, ReleaseInput};
use serde_json::{json, Value};

fn string(value: &Value, key: &str) -> Option<String> {
    value.get(key).and_then(Value::as_str).map(str::to_owned)
}

fn list(value: &Value, key: &str) -> Option<Vec<String>> {
    value.get(key).and_then(Value::as_array).map(|items| {
        items
            .iter()
            .map(|item| item.as_str().expect("list items are strings").to_owned())
            .collect()
    })
}

/// Owned form of a fixture's stream, mirroring the Kotlin `Stream` shape used by `streamTexts`.
struct OwnedStream {
    texts: Vec<String>,
    quality: Option<String>,
    resolve_title: Option<String>,
    parsed: Option<OwnedParsed>,
}

struct OwnedParsed {
    resolution: Option<String>,
    quality: Option<String>,
    codec: Option<String>,
    hdr: Option<Vec<String>>,
    audio: Option<Vec<String>>,
    channels: Option<Vec<String>>,
    languages: Option<Vec<String>>,
    group: Option<String>,
    parsed_title: Option<String>,
}

fn owned_stream(input: &Value) -> OwnedStream {
    let resolve = input.get("resolve");
    let raw = resolve.and_then(|r| r.get("raw"));
    let parsed = raw.and_then(|r| r.get("parsed"));
    let texts = [
        string(input, "name"),
        string(input, "title"),
        string(input, "description"),
        string(input, "quality"),
        resolve.and_then(|r| string(r, "torrentName")),
        resolve.and_then(|r| string(r, "filename")),
        raw.and_then(|r| string(r, "torrentName")),
        raw.and_then(|r| string(r, "filename")),
        string(input, "cachedName"),
    ]
    .into_iter()
    .flatten()
    .collect();
    OwnedStream {
        texts,
        quality: string(input, "quality"),
        resolve_title: resolve.and_then(|r| string(r, "title")),
        parsed: parsed.map(|p| OwnedParsed {
            resolution: string(p, "resolution"),
            quality: string(p, "quality"),
            codec: string(p, "codec"),
            hdr: list(p, "hdr"),
            audio: list(p, "audio"),
            channels: list(p, "channels"),
            languages: list(p, "languages"),
            group: string(p, "group"),
            parsed_title: string(p, "parsedTitle"),
        }),
    }
}

fn borrowed(list: &Option<Vec<String>>) -> Option<Vec<&str>> {
    list.as_ref()
        .map(|items| items.iter().map(String::as_str).collect())
}

fn to_json(facts: &ReleaseFacts) -> Value {
    json!({
        "resolution": facts.resolution.name(),
        "quality": facts.quality.name(),
        "visualTags": facts.visual_tags.iter().map(|t| t.name()).collect::<Vec<_>>(),
        "audioTags": facts.audio_tags.iter().map(|t| t.name()).collect::<Vec<_>>(),
        "audioChannels": facts.audio_channels.iter().map(|t| t.name()).collect::<Vec<_>>(),
        "encode": facts.encode.name(),
        "languages": facts.languages.iter().map(|t| t.name()).collect::<Vec<_>>(),
        "releaseGroup": facts.release_group,
    })
}

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
        let input = ReleaseInput {
            texts: stream.texts.iter().map(String::as_str).collect(),
            stream_quality: stream.quality.as_deref(),
            resolve_title: stream.resolve_title.as_deref(),
            parsed: stream.parsed.as_ref().map(|p| ParsedRelease {
                resolution: p.resolution.as_deref(),
                quality: p.quality.as_deref(),
                codec: p.codec.as_deref(),
                hdr: borrowed(&p.hdr),
                audio: borrowed(&p.audio),
                channels: borrowed(&p.channels),
                languages: borrowed(&p.languages),
                group: p.group.as_deref(),
                parsed_title: p.parsed_title.as_deref(),
            }),
        };
        assert_eq!(&to_json(&classify(&input)), expected, "fixture {name}");
    }
}
