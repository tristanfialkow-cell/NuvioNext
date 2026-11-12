//! Text primitives that reproduce the Kotlin/JVM behaviour the original classifier relies on.
//!
//! The Kotlin implementation uses `java.util.regex` patterns. Rather than translating them into a
//! regex engine with different semantics, each pattern shape is implemented directly here and
//! checked against the Kotlin implementation by shared fixtures and a differential fuzz test.

/// `[a-z0-9]` — the "word" class used by every boundary pattern in the Kotlin classifier.
#[inline]
pub fn is_word(c: char) -> bool {
    c.is_ascii_lowercase() || c.is_ascii_digit()
}

/// Kotlin `String.lowercase()` (`toLowerCase(Locale.ROOT)`).
#[inline]
pub fn lowercase(s: &str) -> String {
    s.to_lowercase()
}

/// Kotlin `Char.isWhitespace()`: `Character.isWhitespace(c) || Character.isSpaceChar(c)`.
///
/// Differs from Rust's `char::is_whitespace` (Unicode `White_Space`): U+001C..U+001F are
/// whitespace here while U+0085 is not.
pub fn kotlin_is_whitespace(c: char) -> bool {
    matches!(
        c,
        '\t' | '\n'
            | '\u{0B}'
            | '\u{0C}'
            | '\r'
            | '\u{1C}'..='\u{1F}'
            | ' '
            | '\u{A0}'
            | '\u{1680}'
            | '\u{2000}'..='\u{200A}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{202F}'
            | '\u{205F}'
            | '\u{3000}'
    )
}

/// Kotlin `String.trim()`.
pub fn kotlin_trim(s: &str) -> &str {
    s.trim_matches(kotlin_is_whitespace)
}

/// Kotlin `CharSequence.isBlank()`.
pub fn kotlin_is_blank(s: &str) -> bool {
    s.chars().all(kotlin_is_whitespace)
}

#[inline]
fn char_before(text: &str, index: usize) -> Option<char> {
    text[..index].chars().next_back()
}

#[inline]
fn char_at(text: &str, index: usize) -> Option<char> {
    text[index..].chars().next()
}

/// Every start offset of `needle` in `text`, including overlapping occurrences.
fn occurrences<'a>(text: &'a str, needle: &'a str) -> impl Iterator<Item = usize> + 'a {
    let mut from = 0usize;
    std::iter::from_fn(move || {
        if needle.is_empty() || from > text.len() {
            return None;
        }
        let found = text[from..].find(needle)? + from;
        // Advance by one character so overlapping occurrences are still considered.
        from = found + char_at(text, found).map_or(1, char::len_utf8);
        Some(found)
    })
}

/// Equivalent of `Regex("(^|[^a-z0-9])(t1|t2|…)([^a-z0-9]|$)").containsMatchIn(text)` with
/// literal tokens.
pub fn contains_bounded_token(text: &str, token: &str) -> bool {
    occurrences(text, token).any(|start| {
        let end = start + token.len();
        char_before(text, start).is_none_or(|c| !is_word(c))
            && char_at(text, end).is_none_or(|c| !is_word(c))
    })
}

pub fn contains_any_bounded_token(text: &str, tokens: &[&str]) -> bool {
    tokens
        .iter()
        .any(|token| contains_bounded_token(text, token))
}

/// Java's non-multiline `$`: end of input, or before a single line terminator that ends the input.
fn java_dollar_matches(text: &str, index: usize) -> bool {
    let rest = &text[index..];
    if rest.is_empty() || rest == "\r\n" {
        return true;
    }
    let mut chars = rest.chars();
    let (Some(c), None) = (chars.next(), chars.next()) else {
        return false;
    };
    match c {
        '\n' => char_before(text, index) != Some('\r'),
        '\r' | '\u{85}' | '\u{2028}' | '\u{2029}' => true,
        _ => false,
    }
}

/// `[a-z0-9]` under `CASE_INSENSITIVE | UNICODE_CASE` (Kotlin adds `UNICODE_CASE` to every
/// `IGNORE_CASE` regex). Java also accepts characters whose upper/lower case lands in range.
fn is_word_ignore_case(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '\u{130}' | '\u{131}' | '\u{17F}' | '\u{212A}')
}

/// `Regex("-([a-z0-9][a-z0-9._]{1,24})($|\\.)", IGNORE_CASE).find(text)?.groupValues[1]`.
pub fn release_group_from_text(text: &str) -> Option<&str> {
    for (dash, _) in text.match_indices('-') {
        let start = dash + 1;
        let mut ends = Vec::with_capacity(25);
        let mut position = start;
        for (count, c) in text[start..].chars().enumerate() {
            let allowed = if count == 0 {
                is_word_ignore_case(c)
            } else {
                is_word_ignore_case(c) || c == '.' || c == '_'
            };
            if !allowed || count == 25 {
                break;
            }
            position += c.len_utf8();
            ends.push(position);
        }
        // Greedy `{1,24}` after the first character: try the longest run first, at least 2 chars.
        for &end in ends.iter().skip(1).rev() {
            if java_dollar_matches(text, end) || char_at(text, end) == Some('.') {
                return Some(&text[start..end]);
            }
        }
    }
    None
}

/// Words of a title as split by Kotlin `title.lowercase().split(Regex("[^a-z0-9]+"))`, dropping
/// empty parts.
pub fn title_words(title: &str) -> Vec<String> {
    lowercase(title)
        .split(|c: char| !is_word(c))
        .filter(|word| !word.is_empty())
        .map(str::to_owned)
        .collect()
}

/// Length of the Kotlin pattern string built from `words`, used for its ordering:
/// `(^|[^a-z0-9])` + words joined by `[^a-z0-9]+` + `(?=[^a-z0-9]|$)`.
pub fn title_pattern_len(words: &[String]) -> usize {
    13 + words.iter().map(String::len).sum::<usize>() + 10 * (words.len() - 1) + 15
}

/// Matches `words` (joined by runs of non-word characters) starting exactly at `index`, followed
/// by a non-word character or the end. Returns the end of the last word.
fn match_title_words_at(text: &str, index: usize, words: &[String]) -> Option<usize> {
    let mut position = index;
    for (i, word) in words.iter().enumerate() {
        if i > 0 {
            let run: usize = text[position..]
                .chars()
                .take_while(|c| !is_word(*c))
                .map(char::len_utf8)
                .sum();
            if run == 0 {
                return None;
            }
            position += run;
        }
        if !text[position..].starts_with(word.as_str()) {
            return None;
        }
        position += word.len();
    }
    char_at(text, position)
        .is_none_or(|c| !is_word(c))
        .then_some(position)
}

/// `Regex("(^|[^a-z0-9])w1[^a-z0-9]+w2…(?=[^a-z0-9]|$)").replaceFirst(text, " ")`.
pub fn replace_first_title(text: &str, words: &[String]) -> Option<String> {
    let first = words.first()?;
    for occurrence in occurrences(text, first) {
        let start = if occurrence == 0 {
            0
        } else {
            match char_before(text, occurrence) {
                Some(c) if !is_word(c) => occurrence - c.len_utf8(),
                _ => continue,
            }
        };
        if let Some(end) = match_title_words_at(text, occurrence, words) {
            let mut replaced = String::with_capacity(text.len());
            replaced.push_str(&text[..start]);
            replaced.push(' ');
            replaced.push_str(&text[end..]);
            return Some(replaced);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bounded_tokens() {
        assert!(contains_bounded_token("movie 2160p hdr", "2160p"));
        assert!(contains_bounded_token("2160p", "2160p"));
        assert!(!contains_bounded_token("x2160p", "2160p"));
        assert!(!contains_bounded_token("2160px", "2160p"));
        assert!(contains_bounded_token("a.7.1.b", "7.1"));
        assert!(contains_bounded_token("dd ddd dd", "dd"));
        assert!(!contains_bounded_token("ddd", "dd"));
        assert!(contains_bounded_token("é4k", "4k"));
    }

    #[test]
    fn release_group_matches_java() {
        assert_eq!(release_group_from_text("movie.2160p-group"), Some("group"));
        assert_eq!(
            release_group_from_text("movie-group.mkv"),
            Some("group.mkv")
        );
        assert_eq!(release_group_from_text("movie-group mkv"), None);
        assert_eq!(release_group_from_text("web-dl x265-ntb\n"), Some("ntb"));
        assert_eq!(release_group_from_text("a-b"), None);
        assert_eq!(release_group_from_text("a-bc"), Some("bc"));
        assert_eq!(release_group_from_text("-.abc"), None);
    }

    #[test]
    fn title_replacement() {
        let words = title_words("The French Dispatch");
        assert_eq!(
            replace_first_title("the.french.dispatch.2021.french.1080p", &words).as_deref(),
            Some(" .2021.french.1080p")
        );
        assert_eq!(
            replace_first_title("x the french dispatch!", &words).as_deref(),
            Some("x !")
        );
        assert_eq!(replace_first_title("thefrench dispatch", &words), None);
        assert_eq!(replace_first_title("the french dispatches", &words), None);
    }

    #[test]
    fn kotlin_whitespace() {
        assert_eq!(kotlin_trim("\u{1C} en \u{A0}"), "en");
        assert_eq!(kotlin_trim("\u{85}en"), "\u{85}en");
        assert!(kotlin_is_blank(" \t\u{3000}"));
        assert!(!kotlin_is_blank(" x "));
    }
}
