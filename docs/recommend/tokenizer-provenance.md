# Походження tokenizer

Runtime tokenizer виконує оголошені в E5 asset правила HF Unigram. Unicode grapheme boundaries беруться з таблиць Unicode 16.0.0, незалежно від версії таблиць у JVM.

## Код і notices

Hugging Face tokenizers 0.22.0, commit `4630f94378998f68df3d021c61a7340b813e264b`:

| Первинне джерело | SHA-256 |
| --- | --- |
| [Unigram model](https://github.com/huggingface/tokenizers/blob/4630f94378998f68df3d021c61a7340b813e264b/tokenizers/src/models/unigram/model.rs) | `85593fb96dfac2710ae0bdb84b767c2605a105f6f9b59f30547aa8bce5059a9a` |
| [Precompiled normalizer](https://github.com/huggingface/tokenizers/blob/4630f94378998f68df3d021c61a7340b813e264b/tokenizers/src/normalizers/precompiled.rs) | `d1c837b9b9e61a3255a54f1e98580e54a96765bcb02711a14aef82a80648c26f` |
| [Metaspace](https://github.com/huggingface/tokenizers/blob/4630f94378998f68df3d021c61a7340b813e264b/tokenizers/src/pre_tokenizers/metaspace.rs) | `0048101657d3d0ab8d6280be30c4ac72d0ee1f00eb2a4ddad9ae45c6f424735b` |
| [Added vocabulary](https://github.com/huggingface/tokenizers/blob/4630f94378998f68df3d021c61a7340b813e264b/tokenizers/src/tokenizer/added_vocabulary.rs) | `9779a20e0ed2fd8ea0197cbe8ed05c58ba05ce4a11b1464ff8d814afd4b95082` |
| [Cargo.lock](https://github.com/huggingface/tokenizers/blob/4630f94378998f68df3d021c61a7340b813e264b/bindings/python/Cargo.lock) | `d551b4492e17df8f2a5ad7c246983d800988bc82e7ec1cbea2db707ae8214c65` |

[HF Apache-2.0](../licenses/tokenizer-HF-APACHE.txt) збережено без змін. Cargo.lock фіксує [spm_precompiled 0.1.4](https://docs.rs/spm_precompiled/0.1.4/src/spm_precompiled/lib.rs.html), crate SHA-256 `5851699c4033c63636f7ea4cf7b7c1f1bf06d0cc03cfb42e711de5a5c46cf326`. Його `src/lib.rs`: `ede2accfcb75390fd0bad3ab091b4c8a9467e18024e270d79c38bb434d88d454`. [Apache-2.0 цього crate](../licenses/tokenizer-precompiled-APACHE.txt) також збережено без змін.

Таблиці й правила grapheme походять із [unicode-segmentation 1.12.0](https://crates.io/crates/unicode-segmentation/1.12.0), crate SHA-256 `f6ccf251212114b54433ec949fd6a7841275f9ada20dddd2f29e9ceea4501493`. `tables.rs`: `e229fbbc93599c5df42c174bdf77602ced0789e1e1c73f7b979a725a56c92da1`; `grapheme.rs`: `bdff7b1459f576e7d02f2547781048a25bae370430be2e2fbe96cf604da1293d`. Для перенесених таблиць збережено [MIT](../licenses/tokenizer-unicode-segmentation-MIT.txt), [COPYRIGHT](../licenses/tokenizer-unicode-segmentation-COPYRIGHT.txt) і [Unicode license V3](../licenses/tokenizer-Unicode-LICENSE.txt).

Історичний [generator](https://github.com/Samuel-Ku/slukhayka/blob/f7a1a8de7d477c3eb7895d210ce4acb82c8df1be/scripts/generate-tokenizer-unicode.py) приймає лише `tables.rs` із наведеною сумою. Саме цей Git commit і файл є джерелом для повторного створення `UnicodeGraphemeTables.kt`. Generator та зовнішній Rust source не потрібні для runtime; вони тут не включені. Copyright у згенерованому файлі збережено.

## Tokenizer asset і fixture

Фактичний tokenizer JSON має Xenova revision `761b726dd34fb83930e26aab4e9ac3899aa1fa78`, SHA-256 `0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39`. Тестовий `e5-precompiled-charsmap.bin` — його точний base64-decoded `normalizer.normalizers[0].precompiled_charsmap`: 237539 bytes, SHA-256 `0942789e0111452f1cc446f70036e26d24458c3c7858c53cc12ca4d87531279b`. Це fixture з наявного asset.

[Upstream E5 model card](https://huggingface.co/intfloat/multilingual-e5-small/raw/614241f622f53c4eeff9890bdc4f31cfecc418b3/README.md) оголошує MIT; SHA-256 card `0038de97aee16258cecbad7ffda4b4febd6953e747a00e0ddbc8e6ed241e9c1c`. Її revision описує license metadata і не замінює revision фактичного Xenova asset. Нового copyright notice для моделі не додано.

Цей документ описує походження коду й fixture. Він не є перевіркою якості рекомендацій.
