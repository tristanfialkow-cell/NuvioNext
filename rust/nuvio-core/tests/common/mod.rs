//! Fixture helpers shared by the fixture test and the benchmark.
#![allow(dead_code)]

use nuvio_core::release_facts::{ParsedRelease, ReleaseFacts, ReleaseInput};
use serde_json::{json, Value};

pub fn string(value: &Value, key: &str) -> Option<String> {
    value.get(key).and_then(Value::as_str).map(str::to_owned)
}

pub fn list(value: &Value, key: &str) -> Option<Vec<String>> {
    value.get(key).and_then(Value::as_array).map(|items| {
        items
            .iter()
            .map(|item| item.as_str().expect("list items are strings").to_owned())
            .collect()
    })
}

/// Owned form of a fixture's stream, mirroring the Kotlin `Stream` shape used by `streamTexts`.
pub struct OwnedStream {
    pub texts: Vec<String>,
    pub quality: Option<String>,
    pub resolve_title: Option<String>,
    pub parsed: Option<OwnedParsed>,
}

pub struct OwnedParsed {
    pub resolution: Option<String>,
    pub quality: Option<String>,
    pub codec: Option<String>,
    pub hdr: Option<Vec<String>>,
    pub audio: Option<Vec<String>>,
    pub channels: Option<Vec<String>>,
    pub languages: Option<Vec<String>>,
    pub group: Option<String>,
    pub parsed_title: Option<String>,
}

pub fn owned_stream(input: &Value) -> OwnedStream {
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

pub fn borrowed(list: &Option<Vec<String>>) -> Option<Vec<&str>> {
    list.as_ref()
        .map(|items| items.iter().map(String::as_str).collect())
}

pub fn to_json(facts: &ReleaseFacts) -> Value {
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

impl OwnedStream {
    /// Borrowed classifier input, as the JNI layer builds it from the Kotlin batch.
    pub fn input(&self) -> ReleaseInput<'_> {
        ReleaseInput {
            texts: self.texts.iter().map(String::as_str).collect(),
            stream_quality: self.quality.as_deref(),
            resolve_title: self.resolve_title.as_deref(),
            parsed: self.parsed.as_ref().map(|p| ParsedRelease {
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
        }
    }
}
