//! Portable core logic for Nuvio Next.
//!
//! This crate has no Android or JNI dependencies so it can be unit-tested on any host. The
//! `nuvio-core-jni` crate exposes it to the app through a narrow, versioned JNI surface.

pub mod kotlin_text;
pub mod release_facts;

/// Version of the Kotlin <-> Rust contract. Bump on any change to the JNI layouts, enum tables or
/// semantics; the Kotlin loader refuses a library whose version does not match its own.
pub const ABI_VERSION: i32 = 1;

/// Crate version, reported by the app's diagnostics.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
