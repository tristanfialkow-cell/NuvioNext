//! Release-name classification for provider/debrid stream results.
//!
//! A line-by-line port of the text classification in
//! `app/src/main/java/com/nuvio/tv/core/debrid/ReleaseFactsClassifier.kt` (Kotlin reference). Ranking,
//! filtering, sorting and size parsing stay in Kotlin; only the CPU-bound text work lives here.
//!
//! Enum discriminants equal the Kotlin enum ordinals. The JNI layer exports [`enum_names`] so the
//! app can verify the tables match before using the library.

#![allow(non_camel_case_types)] // Variant names match the Kotlin enum constants.

use crate::kotlin_text::{
    contains_any_bounded_token, contains_bounded_token, is_word, kotlin_is_blank, kotlin_trim,
    lowercase, release_group_from_text, replace_first_title, title_pattern_len, title_words,
};

macro_rules! ordinal_enum {
    ($name:ident { $($variant:ident),+ $(,)? }) => {
        #[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
        #[repr(u8)]
        pub enum $name { $($variant),+ }

        impl $name {
            pub const ALL: &'static [$name] = &[$($name::$variant),+];
            pub const NAMES: &'static [&'static str] = &[$(stringify!($variant)),+];

            pub fn ordinal(self) -> i32 { self as i32 }
            pub fn name(self) -> &'static str { Self::NAMES[self as usize] }
            pub fn from_name(name: &str) -> Option<Self> {
                Self::NAMES.iter().position(|n| *n == name).map(|i| Self::ALL[i])
            }
        }
    };
}

ordinal_enum!(Resolution {
    P2160,
    P1440,
    P1080,
    P720,
    P576,
    P480,
    P360,
    UNKNOWN
});
ordinal_enum!(Quality {
    BLURAY_REMUX,
    BLURAY,
    WEB_DL,
    WEBRIP,
    HDRIP,
    HD_RIP,
    DVDRIP,
    HDTV,
    CAM,
    TS,
    TC,
    SCR,
    UNKNOWN
});
ordinal_enum!(VisualTag {
    HDR_DV,
    DV_ONLY,
    HDR_ONLY,
    HDR10_PLUS,
    HDR10,
    DV,
    HDR,
    HLG,
    TEN_BIT,
    THREE_D,
    IMAX,
    AI,
    SDR,
    H_OU,
    H_SBS,
    UNKNOWN
});
ordinal_enum!(AudioTag {
    ATMOS,
    DD_PLUS,
    DD,
    DTS_X,
    DTS_HD_MA,
    DTS_HD,
    DTS_ES,
    DTS,
    TRUEHD,
    OPUS,
    FLAC,
    AAC,
    UNKNOWN
});
ordinal_enum!(AudioChannel {
    CH_2_0,
    CH_5_1,
    CH_6_1,
    CH_7_1,
    UNKNOWN
});
ordinal_enum!(Encode {
    AV1,
    HEVC,
    AVC,
    XVID,
    DIVX,
    UNKNOWN
});
ordinal_enum!(Language {
    EN,
    HI,
    IT,
    ES,
    FR,
    DE,
    PT,
    PL,
    CS,
    LA,
    JA,
    KO,
    ZH,
    HE,
    MULTI,
    UNKNOWN
});

/// Order in which the Kotlin classifier appends audio channels (not the enum order).
pub const CHANNEL_ORDER: [AudioChannel; 4] = [
    AudioChannel::CH_7_1,
    AudioChannel::CH_6_1,
    AudioChannel::CH_5_1,
    AudioChannel::CH_2_0,
];

impl Language {
    pub fn code(self) -> &'static str {
        LANGUAGE_TABLE[self as usize].0
    }
    pub fn label(self) -> &'static str {
        LANGUAGE_TABLE[self as usize].1
    }
    pub fn aliases(self) -> &'static [&'static str] {
        LANGUAGE_TABLE[self as usize].2
    }
}

/// (code, label, aliases) per [`Language`] ordinal.
const LANGUAGE_TABLE: [(&str, &str, &[&str]); 16] = [
    ("en", "English", &["eng", "english"]),
    ("hi", "Hindi", &["hin", "hindi"]),
    ("it", "Italian", &["ita", "italian", "italiano"]),
    (
        "es",
        "Spanish",
        &["spa", "esp", "spanish", "espanol", "castellano"],
    ),
    ("fr", "French", &["fre", "french", "truefrench"]),
    ("de", "German", &["ger", "deu", "german", "deutsch"]),
    ("pt", "Portuguese", &["portuguese", "portugues"]),
    ("pl", "Polish", &["pol", "polish"]),
    ("cs", "Czech", &["cze", "czech"]),
    ("la", "Latino", &["latino"]),
    ("ja", "Japanese", &["jpn", "japanese"]),
    ("ko", "Korean", &["kor", "korean"]),
    ("zh", "Chinese", &["chinese"]),
    ("he", "Hebrew", &["heb", "hebrew"]),
    ("multi", "Multi", &[]),
    ("unknown", "Unknown", &[]),
];

const RESOLUTION_TOKENS: [(Resolution, &[&str]); 7] = [
    (Resolution::P2160, &["2160p", "2160", "4k", "uhd"]),
    (Resolution::P1440, &["1440p", "1440", "2k"]),
    (Resolution::P1080, &["1080p", "1080", "fhd"]),
    (Resolution::P720, &["720p", "720", "hd"]),
    (Resolution::P576, &["576p", "576"]),
    (Resolution::P480, &["480p", "480", "sd"]),
    (Resolution::P360, &["360p", "360"]),
];

const DOLBY_VISION_TOKENS: [&str; 6] = [
    "dv",
    "dovi",
    "dolbyvision",
    "dolby vision",
    "dolby.vision",
    "dolby_vision",
];
const DOLBY_VISION_DASH: &str = "dolby-vision";
const HDR_TOKENS: [&str; 5] = ["hdr", "hdr10", "hdr10plus", "hdr10+", "hlg"];

/// Parser output attached by debrid providers (`StreamClientResolveParsed`). `None` lists mean the
/// field was absent, which differs from an empty list in how the search text is joined.
#[derive(Clone, Debug, Default)]
pub struct ParsedRelease<'a> {
    pub resolution: Option<&'a str>,
    pub quality: Option<&'a str>,
    pub codec: Option<&'a str>,
    pub hdr: Option<Vec<&'a str>>,
    pub audio: Option<Vec<&'a str>>,
    pub channels: Option<Vec<&'a str>>,
    pub languages: Option<Vec<&'a str>>,
    pub group: Option<&'a str>,
    pub parsed_title: Option<&'a str>,
}

/// Text inputs of one stream, already extracted from the Kotlin `Stream` model.
#[derive(Clone, Debug, Default)]
pub struct ReleaseInput<'a> {
    /// `streamTexts(stream)` in order: name, title, description, quality, torrent/file names, …
    pub texts: Vec<&'a str>,
    /// `stream.quality`
    pub stream_quality: Option<&'a str>,
    /// `stream.clientResolve?.title`
    pub resolve_title: Option<&'a str>,
    pub parsed: Option<ParsedRelease<'a>>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ReleaseFacts {
    pub resolution: Resolution,
    pub quality: Quality,
    pub visual_tags: Vec<VisualTag>,
    pub audio_tags: Vec<AudioTag>,
    pub audio_channels: Vec<AudioChannel>,
    pub encode: Encode,
    pub languages: Vec<Language>,
    pub release_group: String,
}

impl ReleaseFacts {
    pub fn visual_mask(&self) -> i32 {
        mask(self.visual_tags.iter().map(|t| *t as i32))
    }
    pub fn audio_mask(&self) -> i32 {
        mask(self.audio_tags.iter().map(|t| *t as i32))
    }
    pub fn channel_mask(&self) -> i32 {
        mask(self.audio_channels.iter().map(|t| *t as i32))
    }
}

fn mask(ordinals: impl Iterator<Item = i32>) -> i32 {
    ordinals.fold(0, |acc, ordinal| acc | (1 << ordinal))
}

fn join<'a>(parts: impl IntoIterator<Item = &'a str>) -> String {
    let mut joined = String::new();
    for (i, part) in parts.into_iter().enumerate() {
        if i > 0 {
            joined.push(' ');
        }
        joined.push_str(part);
    }
    joined
}

fn push_unique<T: PartialEq>(values: &mut Vec<T>, value: T) {
    if !values.contains(&value) {
        values.push(value);
    }
}

/// Classifies one stream. Mirrors `ReleaseFactsClassifier.classify(stream)` in Kotlin.
pub fn classify(input: &ReleaseInput<'_>) -> ReleaseFacts {
    let parsed = input.parsed.as_ref();
    let search_text = search_text(input);

    let resolution = [
        parsed.and_then(|p| p.resolution),
        parsed.and_then(|p| p.quality),
        input.stream_quality,
        Some(search_text.as_str()),
    ]
    .into_iter()
    .find_map(|value| resolution_value(value.unwrap_or("")))
    .unwrap_or(Resolution::UNKNOWN);

    let parsed_hdr: &[&str] = parsed.and_then(|p| p.hdr.as_deref()).unwrap_or(&[]);
    let parsed_audio: &[&str] = parsed.and_then(|p| p.audio.as_deref()).unwrap_or(&[]);
    let parsed_channels: &[&str] = parsed.and_then(|p| p.channels.as_deref()).unwrap_or(&[]);

    let quality = stream_quality(parsed.and_then(|p| p.quality), &search_text);
    let visual_tags = visual_tags(parsed_hdr, &search_text);
    let audio_tags = audio_tags(parsed_audio, &search_text);
    let audio_channels = audio_channels(parsed_channels, &search_text);
    let encode = stream_encode(parsed.and_then(|p| p.codec), &search_text);

    let mut languages: Vec<Language> = parsed
        .and_then(|p| p.languages.as_deref())
        .unwrap_or(&[])
        .iter()
        .filter_map(|value| language_for(value))
        .collect();
    if languages.is_empty() {
        let language_text = language_search_text(input);
        languages = Language::ALL
            .iter()
            .copied()
            .filter(|language| {
                contains_bounded_token(&language_text, language.code())
                    || contains_any_bounded_token(&language_text, language.aliases())
            })
            .collect();
    }

    let release_group = parsed
        .and_then(|p| p.group)
        .filter(|group| !kotlin_is_blank(group))
        .map(str::to_owned)
        .unwrap_or_else(|| {
            release_group_from_text(&search_text)
                .unwrap_or("")
                .to_owned()
        });

    ReleaseFacts {
        resolution,
        quality,
        visual_tags,
        audio_tags,
        audio_channels,
        encode,
        languages,
        release_group,
    }
}

fn search_text(input: &ReleaseInput<'_>) -> String {
    let mut parts: Vec<&str> = input.texts.clone();
    let hdr_joined;
    let audio_joined;
    if let Some(parsed) = input.parsed.as_ref() {
        parts.extend(parsed.resolution);
        parts.extend(parsed.quality);
        parts.extend(parsed.codec);
        hdr_joined = parsed.hdr.as_ref().map(|hdr| join(hdr.iter().copied()));
        audio_joined = parsed
            .audio
            .as_ref()
            .map(|audio| join(audio.iter().copied()));
        parts.extend(hdr_joined.as_deref());
        parts.extend(audio_joined.as_deref());
    }
    lowercase(&join(parts))
}

fn resolution_value(value: &str) -> Option<Resolution> {
    let normalized = lowercase(value);
    RESOLUTION_TOKENS
        .iter()
        .find(|(_, tokens)| contains_any_bounded_token(&normalized, tokens))
        .map(|(resolution, _)| *resolution)
}

fn stream_quality(parsed_quality: Option<&str>, search_text: &str) -> Quality {
    let text = lowercase(&join(parsed_quality.into_iter().chain([search_text])));
    let has = |needle: &str| text.contains(needle);
    let token = |needle: &str| contains_bounded_token(&text, needle);
    if has("remux") {
        Quality::BLURAY_REMUX
    } else if has("blu-ray") || has("bluray") || has("bdrip") || has("brrip") {
        Quality::BLURAY
    } else if has("web-dl") || has("webdl") {
        Quality::WEB_DL
    } else if has("webrip") || has("web-rip") {
        Quality::WEBRIP
    } else if has("hdrip") {
        Quality::HDRIP
    } else if has("hd-rip") || has("hcrip") {
        Quality::HD_RIP
    } else if has("dvdrip") {
        Quality::DVDRIP
    } else if has("hdtv") {
        Quality::HDTV
    } else if token("cam") {
        Quality::CAM
    } else if token("ts") {
        Quality::TS
    } else if token("tc") {
        Quality::TC
    } else if token("scr") {
        Quality::SCR
    } else {
        Quality::UNKNOWN
    }
}

/// `(parsed + searchText).joinToString(" ").lowercase()`
fn tag_text(parsed: &[&str], search_text: &str) -> String {
    lowercase(&join(parsed.iter().copied().chain([search_text])))
}

/// Keeps characters for which `keep` holds after lowercasing, like
/// `lowercase().replace(Regex("[^…]"), "")`.
fn squash(value: &str, keep: impl Fn(char) -> bool) -> String {
    lowercase(value).chars().filter(|c| keep(*c)).collect()
}

fn is_dolby_vision_token(value: &str) -> bool {
    matches!(
        squash(value, is_word).as_str(),
        "dv" | "dovi" | "dolbyvision"
    )
}

fn is_hdr_token(value: &str) -> bool {
    matches!(
        squash(value, |c| is_word(c) || c == '+').as_str(),
        "hdr" | "hdr10" | "hdr10+" | "hdr10plus" | "hlg"
    )
}

fn visual_tags(parsed_hdr: &[&str], search_text: &str) -> Vec<VisualTag> {
    let text = tag_text(parsed_hdr, search_text);
    let has = |needle: &str| text.contains(needle);
    let token = |needle: &str| contains_bounded_token(&text, needle);
    let has_dv = parsed_hdr.iter().any(|v| is_dolby_vision_token(v))
        || contains_any_bounded_token(search_text, &DOLBY_VISION_TOKENS)
        || contains_bounded_token(search_text, DOLBY_VISION_DASH);
    let has_hdr = parsed_hdr.iter().any(|v| is_hdr_token(v))
        || contains_any_bounded_token(search_text, &HDR_TOKENS);

    let mut tags = Vec::new();
    if has_dv && has_hdr {
        tags.push(VisualTag::HDR_DV);
    }
    if has_dv && !has_hdr {
        tags.push(VisualTag::DV_ONLY);
    }
    if has_hdr && !has_dv {
        tags.push(VisualTag::HDR_ONLY);
    }
    if has("hdr10+") || has("hdr10plus") {
        tags.push(VisualTag::HDR10_PLUS);
    }
    if has("hdr10") {
        tags.push(VisualTag::HDR10);
    }
    if has_dv {
        tags.push(VisualTag::DV);
    }
    if has_hdr {
        tags.push(VisualTag::HDR);
    }
    if token("hlg") {
        tags.push(VisualTag::HLG);
    }
    if has("10bit") || has("10 bit") {
        tags.push(VisualTag::TEN_BIT);
    }
    if token("3d") {
        tags.push(VisualTag::THREE_D);
    }
    if token("imax") {
        tags.push(VisualTag::IMAX);
    }
    if token("ai") {
        tags.push(VisualTag::AI);
    }
    if token("sdr") {
        tags.push(VisualTag::SDR);
    }
    if has("h-ou") {
        tags.push(VisualTag::H_OU);
    }
    if has("h-sbs") {
        tags.push(VisualTag::H_SBS);
    }
    if tags.is_empty() {
        tags.push(VisualTag::UNKNOWN);
    }
    tags
}

fn audio_tags(parsed_audio: &[&str], search_text: &str) -> Vec<AudioTag> {
    let text = tag_text(parsed_audio, search_text);
    let has = |needle: &str| text.contains(needle);
    let token = |needle: &str| contains_bounded_token(&text, needle);
    let mut tags = Vec::new();
    if token("atmos") {
        tags.push(AudioTag::ATMOS);
    }
    if has("dd+") || has("ddp") || has("dolby digital plus") {
        tags.push(AudioTag::DD_PLUS);
    }
    if token("dd") || has("ac3") || has("dolby digital") {
        tags.push(AudioTag::DD);
    }
    if has("dts:x") || has("dtsx") {
        tags.push(AudioTag::DTS_X);
    }
    if has("dts-hd ma") || has("dtshd ma") {
        tags.push(AudioTag::DTS_HD_MA);
    }
    if has("dts-hd") || has("dtshd") {
        tags.push(AudioTag::DTS_HD);
    }
    if has("dts-es") || has("dtses") {
        tags.push(AudioTag::DTS_ES);
    }
    if token("dts") {
        tags.push(AudioTag::DTS);
    }
    if has("truehd") || has("true hd") {
        tags.push(AudioTag::TRUEHD);
    }
    if token("opus") {
        tags.push(AudioTag::OPUS);
    }
    if token("flac") {
        tags.push(AudioTag::FLAC);
    }
    if token("aac") {
        tags.push(AudioTag::AAC);
    }
    if tags.is_empty() {
        tags.push(AudioTag::UNKNOWN);
    }
    tags
}

fn audio_channels(parsed_channels: &[&str], search_text: &str) -> Vec<AudioChannel> {
    let text = tag_text(parsed_channels, search_text);
    let token = |needle: &str| contains_bounded_token(&text, needle);
    let mut channels = Vec::new();
    if token("7.1") {
        push_unique(&mut channels, AudioChannel::CH_7_1);
    }
    if token("6.1") {
        push_unique(&mut channels, AudioChannel::CH_6_1);
    }
    if token("5.1") || token("6ch") {
        push_unique(&mut channels, AudioChannel::CH_5_1);
    }
    if token("2.0") {
        push_unique(&mut channels, AudioChannel::CH_2_0);
    }
    if channels.is_empty() {
        channels.push(AudioChannel::UNKNOWN);
    }
    channels
}

fn stream_encode(parsed_codec: Option<&str>, search_text: &str) -> Encode {
    let text = lowercase(&join(parsed_codec.into_iter().chain([search_text])));
    let token = |needle: &str| contains_bounded_token(&text, needle);
    if token("av1") {
        Encode::AV1
    } else if token("hevc") || token("h265") || token("x265") {
        Encode::HEVC
    } else if token("avc") || token("h264") || token("x264") {
        Encode::AVC
    } else if token("xvid") {
        Encode::XVID
    } else if token("divx") {
        Encode::DIVX
    } else {
        Encode::UNKNOWN
    }
}

/// Maps a provider language value (code, English label or alias) to a [`Language`].
pub fn language_for(value: &str) -> Option<Language> {
    let normalized = lowercase(kotlin_trim(value));
    Language::ALL.iter().copied().find(|language| {
        normalized == language.code()
            || normalized == lowercase(language.label())
            || language.aliases().contains(&normalized.as_str())
    })
}

/// Stream texts with the media title removed so title words are not read as languages.
fn language_search_text(input: &ReleaseInput<'_>) -> String {
    let mut titles: Vec<Vec<String>> = Vec::new();
    let candidates = [
        input.parsed.as_ref().and_then(|p| p.parsed_title),
        input.resolve_title,
    ];
    for title in candidates.into_iter().flatten() {
        let words = title_words(title);
        if !words.is_empty() && !titles.contains(&words) {
            titles.push(words);
        }
    }
    // Stable sort, longest pattern first, as `sortedByDescending { it.pattern.length }`.
    titles.sort_by_key(|words| std::cmp::Reverse(title_pattern_len(words)));

    let mut result = String::new();
    for (i, text) in input.texts.iter().enumerate() {
        if i > 0 {
            result.push(' ');
        }
        let mut remaining = lowercase(text);
        for words in &titles {
            if let Some(replaced) = replace_first_title(&remaining, words) {
                remaining = replaced;
            }
        }
        result.push_str(&remaining);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(texts: &[&'static str]) -> ReleaseInput<'static> {
        ReleaseInput {
            texts: texts.to_vec(),
            ..Default::default()
        }
    }

    #[test]
    fn classifies_common_release_name() {
        let facts = classify(&input(&[
            "Torrentio\n4k DV HDR",
            "Movie.2023.2160p.BluRay.REMUX.DV.HDR10.TrueHD.Atmos.7.1.HEVC-FraMeSToR",
        ]));
        assert_eq!(facts.resolution, Resolution::P2160);
        assert_eq!(facts.quality, Quality::BLURAY_REMUX);
        assert_eq!(
            facts.visual_tags,
            vec![
                VisualTag::HDR_DV,
                VisualTag::HDR10,
                VisualTag::DV,
                VisualTag::HDR
            ]
        );
        assert_eq!(facts.audio_tags, vec![AudioTag::ATMOS, AudioTag::TRUEHD]);
        assert_eq!(facts.audio_channels, vec![AudioChannel::CH_7_1]);
        assert_eq!(facts.encode, Encode::HEVC);
        assert_eq!(facts.release_group, "framestor");
        assert!(facts.languages.is_empty());
    }

    #[test]
    fn parsed_languages_keep_order_and_duplicates() {
        let facts = classify(&ReleaseInput {
            texts: vec!["x"],
            parsed: Some(ParsedRelease {
                languages: Some(vec!["English", " en ", "xx", "ita"]),
                ..Default::default()
            }),
            ..Default::default()
        });
        assert_eq!(
            facts.languages,
            vec![Language::EN, Language::EN, Language::IT]
        );
    }

    #[test]
    fn title_words_are_not_languages() {
        let facts = classify(&ReleaseInput {
            texts: vec!["The.French.Dispatch.2021.1080p.WEB-DL"],
            resolve_title: Some("The French Dispatch"),
            ..Default::default()
        });
        assert!(facts.languages.is_empty());
        assert_eq!(facts.quality, Quality::WEB_DL);
    }

    #[test]
    fn enum_names_round_trip() {
        for tag in VisualTag::ALL {
            assert_eq!(VisualTag::from_name(tag.name()), Some(*tag));
        }
        assert_eq!(Language::MULTI.code(), "multi");
        assert_eq!(Language::ALL.len(), LANGUAGE_TABLE.len());
    }
}
