# Third-party code

| Component | Version | Licence | Where |
| --- | --- | --- | --- |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) (with ggml) | tag `b12525`, commit `edec462cc00abebc50610b82ac06c6cde266e5d0` | MIT, see `llama.cpp/LICENSE` | git submodule `third_party/llama.cpp`, built from source by `:llamaengine` |
| nlohmann/json (vendored in llama.cpp) | as in that commit | MIT, see `llama.cpp/licenses/LICENSE-jsonhpp` | linked into `libllama-common.so` |
| cpp-httplib (vendored in llama.cpp) | as in that commit | MIT, see `llama.cpp/vendor/cpp-httplib/LICENSE` | compiled into `libllama-common.so`, never called |
| mtmd, llama.cpp's image reader (`tools/mtmd`) | as in that commit | MIT, as llama.cpp | `libmtmd.so` |
| stb_image (vendored in llama.cpp) | v2.32, as in that commit | public domain or MIT, see the end of `llama.cpp/vendor/stb/stb_image.h` | compiled into `libmtmd.so`, decodes images |
| miniaudio (vendored in llama.cpp) | as in that commit | public domain or MIT-0, see the end of `llama.cpp/vendor/miniaudio/miniaudio.h` | compiled into `libmtmd.so` for audio models, not used by the app |
| SHA-1, SHA-256, xxHash, rotate-bits (vendored in llama.cpp, `vendor/hash`) | as in that commit | public domain, public domain, BSD 2-Clause and MIT, see the `LICENSE` files there | linked into `libmtmd.so` and `libllama-common.so` |
| Inter typeface | 4.x | SIL Open Font License 1.1, see `inter/OFL.txt` | `:design` |

The app shows these licences under About → Open-source licences
(`llamaengine/src/main/assets/licenses/llama.cpp-MIT.txt` and `llama.cpp-vendored.txt`).

To update llama.cpp: `git -C third_party/llama.cpp fetch --depth 1 origin tag <tag> && git -C third_party/llama.cpp checkout <tag>`,
then rebuild, run the tests and the emulator check, and update the tag and commit here, in the README and in the licence asset.
