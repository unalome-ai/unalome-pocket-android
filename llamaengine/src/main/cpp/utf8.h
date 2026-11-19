#pragma once

#include <cstddef>
#include <string>

namespace unalome {

// Length of the longest prefix of `bytes` that does not end inside a UTF-8 sequence.
//
// A token can end halfway through a character (emoji, CJK, accented letters in byte-level
// vocabularies). Those trailing bytes are held back until the next token completes them, so the
// text handed on never contains half a character. Malformed bytes are not held back: they would
// never complete, and the Kotlin decoder turns them into U+FFFD.
inline size_t utf8_complete_prefix(const std::string & bytes) {
    const size_t n = bytes.size();
    // A sequence is at most 4 bytes, so only the last 3 can belong to an unfinished one.
    const size_t lookback = n < 3 ? n : 3;
    for (size_t back = 1; back <= lookback; ++back) {
        const auto c = static_cast<unsigned char>(bytes[n - back]);
        if ((c & 0xC0) == 0x80) {
            continue; // continuation byte: keep looking for the lead byte
        }
        size_t expected;
        if ((c & 0x80) == 0x00) {
            expected = 1;
        } else if ((c & 0xE0) == 0xC0) {
            expected = 2;
        } else if ((c & 0xF0) == 0xE0) {
            expected = 3;
        } else if ((c & 0xF8) == 0xF0) {
            expected = 4;
        } else {
            return n; // not a valid lead byte
        }
        return back < expected ? n - back : n;
    }
    return n; // only continuation bytes at the end: malformed, pass through
}

} // namespace unalome
