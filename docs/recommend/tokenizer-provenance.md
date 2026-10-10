# Походження tokenizer v3

Повні експерименти v1 і v2 дали NO-GO. Їхні входи, звіти та ранги збережені без змін. Потім окрема перевірка 15 текстів, зафіксованих до отримання token IDs і поза бібліографічними групами, знайшла різницю з Hugging Face. Невідомий Unicode scalar обривав відомий суфікс; NFKC не виконував оголошений Precompiled charsmap. Виправлення v3 стосується цього контракту. Воно не змінює модель, книжки, мітки, тексти кандидатів, ranking weights або пороги GO.

## Первинні джерела

Hugging Face tokenizers — tag 0.22.0, commit `4630f94378998f68df3d021c61a7340b813e264b`:

| Файл | SHA-256 |
| --- | --- |
| [Unigram model](https://github.com/huggingface/tokenizers/blob/4630f94378998f68df3d021c61a7340b813e264b/tokenizers/src/models/unigram/model.rs) | `85593fb96dfac2710ae0bdb84b767c2605a105f6f9b59f30547aa8bce5059a9a` |
| [Precompiled normalizer](https://github.com/huggingface/tokenizers/blob/4630f94378998f68df3d021c61a7340b813e264b/tokenizers/src/normalizers/precompiled.rs) | `d1c837b9b9e61a3255a54f1e98580e54a96765bcb02711a14aef82a80648c26f` |
| [Metaspace](https://github.com/huggingface/tokenizers/blob/4630f94378998f68df3d021c61a7340b813e264b/tokenizers/src/pre_tokenizers/metaspace.rs) | `0048101657d3d0ab8d6280be30c4ac72d0ee1f00eb2a4ddad9ae45c6f424735b` |
| [Added vocabulary](https://github.com/huggingface/tokenizers/blob/4630f94378998f68df3d021c61a7340b813e264b/tokenizers/src/tokenizer/added_vocabulary.rs) | `9779a20e0ed2fd8ea0197cbe8ed05c58ba05ce4a11b1464ff8d814afd4b95082` |
| [Cargo.lock](https://github.com/huggingface/tokenizers/blob/4630f94378998f68df3d021c61a7340b813e264b/bindings/python/Cargo.lock) | `d551b4492e17df8f2a5ad7c246983d800988bc82e7ec1cbea2db707ae8214c65` |

Ліцензія HF Apache-2.0 з цієї ревізії збережена [без змін](../licenses/tokenizer-HF-APACHE.txt), SHA-256 `c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4`.

Cargo.lock фіксує [spm_precompiled 0.1.4](https://docs.rs/spm_precompiled/0.1.4/src/spm_precompiled/lib.rs.html), crate SHA-256 `5851699c4033c63636f7ea4cf7b7c1f1bf06d0cc03cfb42e711de5a5c46cf326`. `src/lib.rs` має SHA-256 `ede2accfcb75390fd0bad3ab091b4c8a9467e18024e270d79c38bb434d88d454`. Збережено [його Apache-2.0 текст](../licenses/tokenizer-precompiled-APACHE.txt).

Також зафіксовано [unicode-segmentation 1.12.0](https://crates.io/crates/unicode-segmentation/1.12.0), crate SHA-256 `f6ccf251212114b54433ec949fd6a7841275f9ada20dddd2f29e9ceea4501493`. Unicode 16 tables.rs має SHA-256 `e229fbbc93599c5df42c174bdf77602ced0789e1e1c73f7b979a725a56c92da1`, grapheme.rs — `bdff7b1459f576e7d02f2547781048a25bae370430be2e2fbe96cf604da1293d`. Таблиці перенесені під [MIT](../licenses/tokenizer-unicode-segmentation-MIT.txt); [COPYRIGHT](../licenses/tokenizer-unicode-segmentation-COPYRIGHT.txt) збережено. Generator відкидає джерело з іншою сумою:

```sh
python3 scripts/generate-tokenizer-unicode.py --unicode-source /path/to/unicode-segmentation-1.12.0/src/tables.rs --output /tmp/UnicodeGraphemeTables.kt
```

Згенерований файл має побайтно збігатися з production `UnicodeGraphemeTables.kt`. Production segmenter використовує ці таблиці, а не залежні від JVM Unicode властивості чи `Pattern \X`.

## Assets і fixtures

Tokenizer asset лишається в ревізії Xenova `761b726dd34fb83930e26aab4e9ac3899aa1fa78`, SHA-256 `0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39`. Тестовий `e5-precompiled-charsmap.bin` — точне base64-декодування `normalizer.normalizers[0].precompiled_charsmap` цього JSON. Розмір 237 539 bytes, SHA-256 `0942789e0111452f1cc446f70036e26d24458c3c7858c53cc12ca4d87531279b`. Це fixture з asset, не новий tokenizer чи інша модель.

[Офіційна upstream E5 model card](https://huggingface.co/intfloat/multilingual-e5-small/raw/614241f622f53c4eeff9890bdc4f31cfecc418b3/README.md) оголошує MIT; її SHA-256 `0038de97aee16258cecbad7ffda4b4febd6953e747a00e0ddbc8e6ed241e9c1c`. Ревізія card `614241f622f53c4eeff9890bdc4f31cfecc418b3` служить джерелом license metadata. Вона не замінює ревізію фактичних Xenova assets `761b…`; нового copyright notice не додаю.

Тестові Unicode boundaries походять із [офіційного GraphemeBreakTest 16.0.0](https://www.unicode.org/Public/16.0.0/ucd/auxiliary/GraphemeBreakTest.txt): 1 093 випадки, 171 927 bytes, SHA-256 `ee2b9354d270ac061b29f09662cafea06341d77e704b8cc6bd72aaeeda363cb5`. Fixture збережений без змін. [Unicode license](../licenses/tokenizer-Unicode-LICENSE.txt) також збережений, SHA-256 `e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96`.

## Підтриманий контракт

Viterbi переходить за Unicode scalars, додає unknown edge лише за відсутності односкалярного piece зі score `minimum vocabulary score - 10`, залишає перший шлях за рівності score та зливає сусідні unknown IDs у межах одного сегмента. Precompiled виконує Darts charsmap. Як у pinned spm_precompiled, цілий grapheme перевіряється лише при UTF-8 довжині <6; використовується перший, найкоротший prefix match. Без такого match виконується scalar loop. Це збережений контракт HF, а не наближення через NFKC.

Нормалізатори виконуються в оголошеному порядку. Replace підтримує точний оголошений Regex ` {2,}` → ASCII space. Metaspace замінює лише ASCII space на `▁`, prepend Always і split MergedWithNext, зберігаючи останній `▁`. Raw added tokens (`normalized=false`) виділяються до normalizer; earliest match і найдовший literal за однакової позиції. Для bundled п'яти tokens усі `single_word`, `lstrip`, `rstrip` false. True flags, інші normalizers/Replace/Metaspace options, byte fallback, padding/truncation і malformed binary map відхиляються. Відсутній normalizer/pre-tokenizer означає оголошену відсутність; null усередині Sequence — помилка. Явний NFKC підтриманий для малих тестових конфігурацій; bundled Precompiled path його не використовує.

Model template 0/A/2, `query: `, maxLength 512 і mean/L2 pooling лишаються як у v2. Raw encode не обрізається. Context v3 відрізняється від v2; усі tokenizer helpers включені в backend і protocol hashes, тому старі вектори не використовуються повторно.

Окрема перевірка tokenizer IDs і Unicode boundaries доводить сумісність перевірених входів, а не якість рекомендацій. Source `f7a1a8de` та report-template-only `9ed54c4a` пройшли незалежні reviews. Input ledger зафіксовано до нового інференсу 2026-10-04 о 19:11:45.072137 UTC та незалежно звірено; SHA-256 `4d33b8393bf533fae886db9150317bab95c2911c35b4f93fa4103409e465ebec`, freeze/run HEAD `009cb87b`. Actual cold 53010 і standard replay 35391 дали NO-GO 0/0 для обох моделей на 24 fold. [Незмінний архів v3](experiments/2026-10-04-hf-tokenizer-v3/README.md) зберігає докази. V1/v2 не змінено; вимога кращої якості E5 ще не виконана.

## Окремий експеримент recording-text-v4

[Незмінний v4](experiments/2026-10-04-recording-text-v4/README.md) змінює лише обмежене очищення production Work text; tokenizer/backend та його v3 contract незмінні. Source `4e1347a4`, ledger SHA-256 `3e0dcb9e05280c93635ba2ddaad5a5beed737411b33185f1dc69d15c37ece64e` зафіксовано 2026-10-05 о 07:05:21.114424 UTC без інференсу й незалежно звірено до cold. Freeze/run HEAD — `61bc5614`. Candidate text hash змінив journals обох моделей. Actual cold 48390 і standard replay 50131 дали NO-GO 0/0 на 24 fold. Докази 98 tokenizer порівнянь та 1 093 Unicode випадків не змінено; це не доказ кращих рекомендацій. Архіви v1/v2/v3 лишаються незмінними.
