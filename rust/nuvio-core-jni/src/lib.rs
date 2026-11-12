//! JNI entry points for `com.nuvio.tv.core.nativecore.NuvioCoreNative`.
//!
//! Contract (ABI version [`nuvio_core::ABI_VERSION`]):
//! - All functions are pure and reentrant: no global state, no Java callbacks, no threads.
//!   They may be called concurrently from any Java thread.
//! - Inputs are flat primitive/String arrays (no JSON); the layout is documented on
//!   [`Java_com_nuvio_tv_core_nativecore_NuvioCoreNative_nativeClassifyReleases`] and mirrored in
//!   `NativeReleaseFactsCodec.kt`.
//! - Invalid input throws `IllegalArgumentException`; a Rust panic is caught and rethrown as
//!   `IllegalStateException`. Native code never aborts the process for recoverable errors.

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JIntArray, JObject, JObjectArray, JString};
use jni::sys::{jint, jobjectArray, jstring};
use jni::JNIEnv;
use nuvio_core::release_facts::{
    classify, AudioChannel, AudioTag, Encode, Language, ParsedRelease, Quality, ReleaseInput,
    Resolution, VisualTag,
};

/// Ints per stream in the input layout: textCount, parsedFlag, hdrCount, audioCount,
/// channelsCount, languagesCount (list counts are -1 for an absent list).
const LAYOUT_STRIDE: usize = 6;
/// Fixed nullable strings per stream: streamQuality, resolveTitle, parsed resolution, quality,
/// codec, group and parsedTitle.
const FIXED_STRINGS: usize = 7;
/// Ints per stream in the output: resolution, quality, visualMask, audioMask, channelMask,
/// encode, languageStart, languageCount.
const OUT_STRIDE: usize = 8;

enum Failure {
    Argument(String),
    Jni(jni::errors::Error),
}

impl From<jni::errors::Error> for Failure {
    fn from(error: jni::errors::Error) -> Self {
        Failure::Jni(error)
    }
}

fn argument(message: impl Into<String>) -> Failure {
    Failure::Argument(message.into())
}

/// Runs `body`, converting errors and panics into pending Java exceptions.
fn guarded<'local, T>(
    env: &mut JNIEnv<'local>,
    fallback: T,
    body: impl FnOnce(&mut JNIEnv<'local>) -> Result<T, Failure>,
) -> T {
    let outcome = catch_unwind(AssertUnwindSafe(|| body(env)));
    let (class, message) = match outcome {
        Ok(Ok(value)) => return value,
        Ok(Err(Failure::Argument(message))) => ("java/lang/IllegalArgumentException", message),
        Ok(Err(Failure::Jni(jni::errors::Error::JavaException))) => return fallback,
        Ok(Err(Failure::Jni(error))) => ("java/lang/IllegalStateException", error.to_string()),
        Err(panic) => {
            let detail = panic
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| panic.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".to_owned());
            (
                "java/lang/IllegalStateException",
                format!("nuvio_core panic: {detail}"),
            )
        }
    };
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new(class, message);
    }
    fallback
}

#[no_mangle]
pub extern "system" fn Java_com_nuvio_tv_core_nativecore_NuvioCoreNative_nativeAbiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    nuvio_core::ABI_VERSION
}

#[no_mangle]
pub extern "system" fn Java_com_nuvio_tv_core_nativecore_NuvioCoreNative_nativeVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        Ok(env.new_string(nuvio_core::VERSION)?.into_raw())
    })
}

/// Enum tables used by the Kotlin side to verify both sides agree before enabling the library.
/// Kinds: 0 resolution, 1 quality, 2 visual tag, 3 audio tag, 4 audio channel, 5 encode,
/// 6 language names, 7 language codes, 8 language labels, 9 language aliases (comma-joined).
#[no_mangle]
pub extern "system" fn Java_com_nuvio_tv_core_nativecore_NuvioCoreNative_nativeEnumTable<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    kind: jint,
) -> jobjectArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let values: Vec<String> = match kind {
            0 => owned(Resolution::NAMES),
            1 => owned(Quality::NAMES),
            2 => owned(VisualTag::NAMES),
            3 => owned(AudioTag::NAMES),
            4 => owned(AudioChannel::NAMES),
            5 => owned(Encode::NAMES),
            6 => owned(Language::NAMES),
            7 => Language::ALL.iter().map(|l| l.code().to_owned()).collect(),
            8 => Language::ALL.iter().map(|l| l.label().to_owned()).collect(),
            9 => Language::ALL
                .iter()
                .map(|l| l.aliases().join(","))
                .collect(),
            _ => return Err(argument(format!("unknown enum table {kind}"))),
        };
        let array =
            env.new_object_array(values.len() as jint, "java/lang/String", JObject::null())?;
        for (i, value) in values.iter().enumerate() {
            let string = env.new_string(value)?;
            env.set_object_array_element(&array, i as jint, &string)?;
            env.delete_local_ref(string)?;
        }
        Ok(array.into_raw())
    })
}

fn owned(names: &[&str]) -> Vec<String> {
    names.iter().map(|n| (*n).to_owned()).collect()
}

fn read_strings(env: &mut JNIEnv, array: &JObjectArray) -> Result<Vec<Option<String>>, Failure> {
    let length = env.get_array_length(array)? as usize;
    let mut values = Vec::with_capacity(length);
    for i in 0..length {
        let element = env.get_object_array_element(array, i as jint)?;
        if element.is_null() {
            values.push(None);
        } else {
            let string = JString::from(element);
            let value: String = env.get_string(&string)?.into();
            // Release each local reference: large batches would overflow the local reference table.
            env.delete_local_ref(string)?;
            values.push(Some(value));
        }
    }
    Ok(values)
}

fn read_ints(env: &mut JNIEnv, array: &JIntArray) -> Result<Vec<jint>, Failure> {
    let length = env.get_array_length(array)? as usize;
    let mut values = vec![0; length];
    env.get_int_array_region(array, 0, &mut values)?;
    Ok(values)
}

struct Cursor<'a> {
    strings: &'a [Option<String>],
    position: usize,
}

impl<'a> Cursor<'a> {
    fn next(&mut self) -> Result<Option<&'a str>, Failure> {
        let value = self
            .strings
            .get(self.position)
            .ok_or_else(|| argument("strings array is shorter than the layout requires"))?;
        self.position += 1;
        Ok(value.as_deref())
    }

    fn next_required(&mut self) -> Result<&'a str, Failure> {
        self.next()?.ok_or_else(|| {
            argument(format!(
                "null list element at strings[{}]",
                self.position - 1
            ))
        })
    }

    fn list(&mut self, count: jint) -> Result<Option<Vec<&'a str>>, Failure> {
        match count {
            -1 => Ok(None),
            n if n >= 0 => (0..n)
                .map(|_| self.next_required())
                .collect::<Result<_, _>>()
                .map(Some),
            n => Err(argument(format!("invalid list count {n}"))),
        }
    }
}

fn decode_inputs<'a>(
    layout: &[jint],
    strings: &'a [Option<String>],
) -> Result<Vec<ReleaseInput<'a>>, Failure> {
    if layout.len() % LAYOUT_STRIDE != 0 {
        return Err(argument("layout length is not a multiple of the stride"));
    }
    let mut cursor = Cursor {
        strings,
        position: 0,
    };
    let mut inputs = Vec::with_capacity(layout.len() / LAYOUT_STRIDE);
    for header in layout.chunks_exact(LAYOUT_STRIDE) {
        let &[text_count, parsed_flag, hdr, audio, channels, languages] = header else {
            unreachable!()
        };
        let mut fixed = [None; FIXED_STRINGS];
        for slot in fixed.iter_mut() {
            *slot = cursor.next()?;
        }
        let [stream_quality, resolve_title, resolution, quality, codec, group, parsed_title] =
            fixed;
        if text_count < 0 {
            return Err(argument(format!("invalid text count {text_count}")));
        }
        let texts = (0..text_count)
            .map(|_| cursor.next_required())
            .collect::<Result<Vec<_>, _>>()?;
        let hdr = cursor.list(hdr)?;
        let audio = cursor.list(audio)?;
        let channels = cursor.list(channels)?;
        let languages = cursor.list(languages)?;
        let parsed = match parsed_flag {
            0 => None,
            1 => Some(ParsedRelease {
                resolution,
                quality,
                codec,
                hdr,
                audio,
                channels,
                languages,
                group,
                parsed_title,
            }),
            other => return Err(argument(format!("invalid parsed flag {other}"))),
        };
        inputs.push(ReleaseInput {
            texts,
            stream_quality,
            resolve_title,
            parsed,
        });
    }
    if cursor.position != strings.len() {
        return Err(argument(
            "strings array is longer than the layout describes",
        ));
    }
    Ok(inputs)
}

/// Classifies a batch of streams.
///
/// `layout` holds [`LAYOUT_STRIDE`] ints per stream. `strings` holds, per stream, the
/// [`FIXED_STRINGS`] nullable strings followed by the texts, hdr, audio, channels and languages
/// elements (non-null). Results are written to `out` ([`OUT_STRIDE`] ints per stream; masks use
/// enum ordinals as bit indices), `languages_out` (language ordinals referenced by
/// start/count) and `groups_out` (release group, `null` when empty). Returns the stream count.
#[no_mangle]
pub extern "system" fn Java_com_nuvio_tv_core_nativecore_NuvioCoreNative_nativeClassifyReleases<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    layout: JIntArray<'local>,
    strings: JObjectArray<'local>,
    out: JIntArray<'local>,
    languages_out: JIntArray<'local>,
    groups_out: JObjectArray<'local>,
) -> jint {
    guarded(&mut env, -1, |env| {
        let layout = read_ints(env, &layout)?;
        let strings = read_strings(env, &strings)?;
        let inputs = decode_inputs(&layout, &strings)?;
        let count = inputs.len();

        if env.get_array_length(&out)? as usize != count * OUT_STRIDE {
            return Err(argument("out array has the wrong size"));
        }
        if env.get_array_length(&groups_out)? as usize != count {
            return Err(argument("groups array has the wrong size"));
        }
        let language_capacity = env.get_array_length(&languages_out)? as usize;

        let mut results = Vec::with_capacity(count * OUT_STRIDE);
        let mut languages = Vec::new();
        for (i, input) in inputs.iter().enumerate() {
            let facts = classify(input);
            results.extend_from_slice(&[
                facts.resolution.ordinal(),
                facts.quality.ordinal(),
                facts.visual_mask(),
                facts.audio_mask(),
                facts.channel_mask(),
                facts.encode.ordinal(),
                languages.len() as jint,
                facts.languages.len() as jint,
            ]);
            languages.extend(facts.languages.iter().map(|l| l.ordinal()));
            if !facts.release_group.is_empty() {
                let group = env.new_string(&facts.release_group)?;
                env.set_object_array_element(&groups_out, i as jint, &group)?;
                env.delete_local_ref(group)?;
            }
        }
        if languages.len() > language_capacity {
            return Err(argument(format!(
                "languages array too small: need {}, have {language_capacity}",
                languages.len()
            )));
        }
        env.set_int_array_region(&out, 0, &results)?;
        env.set_int_array_region(&languages_out, 0, &languages)?;
        Ok(count as jint)
    })
}
