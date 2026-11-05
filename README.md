# NeuralKeyboard

แป้นพิมพ์อัจฉริยะสำหรับ Android (ภาษาไทย + อังกฤษ) ที่ประมวลผลทั้งหมดบนเครื่อง ไม่ส่งข้อมูลการพิมพ์ออกไปไหน

A smart on-device Android keyboard (Thai + English).

## ความสามารถ / Features

| ฟีเจอร์ | ตัวอย่าง |
|---|---|
| เติมคำอัตโนมัติ (completion) | `hel` → hello, help · `สวัส` → สวัสดี |
| แก้คำผิดเมื่อกดเว้นวรรค (auto-correct) | `teh` → the · `dont` → don't · `ขอบคุน` → ขอบคุณ |
| รู้ตำแหน่งปุ่ม — กดโดนปุ่มข้างเคียงถือว่าผิดน้อยกว่า | `hrllo` → hello (r อยู่ติดกับ e) |
| ลืมใส่วรรณยุกต์ (ไทย) | `ไมเป็นไร` → ไม่เป็นไร |
| เดาคำถัดไป (next-word prediction) | หลัง `thank` → you · หลัง `ขอบคุณ` → ครับ |
| ตัดคำไทยอัตโนมัติ (ภาษาไทยไม่มีเว้นวรรค) | `สวัสดีค` → commit "สวัสดี", พิมพ์ต่อ "ค" → แนะนำ "ครับ" |
| เรียนรู้คำใหม่และคู่คำที่ใช้บ่อยของผู้ใช้ | ชื่อคน/ศัพท์เฉพาะจะไม่ถูกแก้อีก และขึ้นในคำแนะนำ |
| กด ⌫ ทันทีหลังแก้คำผิด = ยกเลิกการแก้ | `teh␣` → `the ` → ⌫ → `teh` |
| ขึ้นต้นประโยคตัวใหญ่, เว้นวรรคสองครั้งใส่จุด, ย้ายเว้นวรรคก่อน `.` `,` | |
| คลิปบอร์ด 📋 | คัดลอกแล้วมีปุ่ม "วาง" ขึ้นทันที, ประวัติ 1 ชั่วโมง, 📌 ปักหมุดเก็บถาวร, ไม่เก็บข้อความที่แอปทำเครื่องหมายว่าลับ (เช่น รหัสผ่าน) |
| ชื่อยา 💊 | ~9,700 ชื่อยาสามัญ (อังกฤษ) + ~110 ชื่อยาภาษาไทย: `cispl` → cisplatin, `insulin` → glargine, `พาราเซ` → พาราเซตามอล |
| ความปลอดภัยของชื่อยา | ชื่อยา **แนะนำได้แต่ไม่ถูกแทนอัตโนมัติ** ป้องกันการเปลี่ยนเป็นยาชื่อคล้าย (เช่น hydroxyzine ↔ hydralazine) |
| ข้อความลัด 📝 | พิมพ์ `soap` → แตะข้อความเต็มในช่องแนะนำ, หรือ `;soap` + เว้นวรรค ใส่ทันที; รองรับ `{cursor}` `{date}` `{time}` `{clipboard}`; จัดการในแอป → "จัดการข้อความลัด" |
| เอฟเฟกต์ปุ่ม | ตัวอักษรขยายเหนือนิ้ว + ปุ่มยุบแล้วเด้งกลับ (ปิดได้ในตั้งค่า) |
| ความเป็นส่วนตัว | ไม่เดา/ไม่เรียนรู้ในช่องรหัสผ่าน, อีเมล/URL, หรือแอปที่ขอโหมด incognito |

## โครงสร้าง / Structure

```
core/   Pure Kotlin prediction engine (unit-tested on the JVM)
  Trie.kt              frequency trie + keyboard-aware Damerau-Levenshtein search
  KeyProximity.kt      edit costs from physical key positions, Thai tone/vowel rules
  LanguageModel.kt     unigram + bigram model, on-device learning, save/load
  SuggestionEngine.kt  completion, correction, prediction, auto-correct decision, Thai word split
  Language.kt          English QWERTY + Thai Kedmanee layouts
app/    Android app
  SmartKeyboardService.kt  InputMethodService: composing text, autocorrect, undo, learning
  KeyboardUi.kt            keys + suggestion strip (built in code, light/dark theme)
  SettingsActivity.kt      setup wizard + preferences + test box
  assets/dict/             word-frequency and bigram lists (en, th)
tools/
  build_thai_dict.py       regenerates th_words.tsv from the Thai National Corpus
  build_english_dict.py    regenerates en_words.tsv / en_bigrams.tsv from SymSpell data
  th_curated.tsv           hand-picked chat words (ครับ, ค่ะ, จ้า, ...) merged into it
```

## พจนานุกรม / Dictionaries

- **ไทย: ~23,000 คำ** พร้อมความถี่จาก Thai National Corpus (ผ่าน [PyThaiNLP corpus](https://github.com/PyThaiNLP/pythainlp-corpus), สัญญาอนุญาต CC0-1.0)
  กรองคำขยะออก (คำที่ตัดผิด, วรรณยุกต์ซ้ำ, ขึ้นต้นด้วยสระ) และรวมคำภาษาพูดจาก `tools/th_curated.tsv`
  สร้างใหม่ได้ด้วย `python3 tools/build_thai_dict.py tnc_freq.txt words_th.txt` (ดูวิธีโหลดไฟล์ในสคริปต์)
- **อังกฤษ: ~40,000 คำ + ~72,000 คู่คำ** (สำหรับเดาคำถัดไป) จากข้อมูลความถี่ของ [SymSpell](https://github.com/wolfgarbe/SymSpell) (MIT)
  ซึ่งสร้างจาก Google Books Ngram (CC BY 3.0) และ SCOWL รวมกับคำย่อ/คำที่ไม่มี apostrophe ใน `tools/en_curated*.tsv`
  สร้างใหม่ได้ด้วย `python3 tools/build_english_dict.py ...` (ดูวิธีโหลดไฟล์ในสคริปต์)
- **ชื่อยา (อังกฤษ): ~9,700 ชื่อยาสามัญ** จาก DrugBank Open Data (CC0-1.0) ผ่านแพ็กเกจ `drug-named-entity-recognition` (MIT)
  สร้างใหม่ด้วย `tools/build_drug_list.py`, เพิ่มชื่อ INN/BAN ที่ใช้ในไทยได้ที่ `tools/en_medical_extra.txt`
  ยังไม่มีชื่อการค้า (brand names)
- **ชื่อยา (ไทย): ~110 ชื่อ** คัดด้วยมือใน `assets/dict/th_medical.tsv` (การสะกดทับศัพท์มีหลายแบบ เพิ่มได้)
- ปิด/เปิดคำศัพท์การแพทย์ได้ในหน้าตั้งค่า
- **คำย่อ/shortcut** (`assets/dict/en_shortcuts.tsv`): `im` → I'm, `dont` → don't, `thx` → thanks, `pls` → please, `idk` → I don't know

## Build & ติดตั้ง

```bash
./gradlew :core:test            # ทดสอบระบบเดาคำ (ไม่ต้องมี Android SDK)
./gradlew :app:assembleDebug    # ต้องมี Android SDK (ANDROID_HOME หรือ local.properties)
adb install app/build/outputs/apk/debug/app-debug.apk
```

หรือดาวน์โหลด APK ที่ GitHub Actions สร้างให้ (แท็บ **Actions** → run ล่าสุด → artifact `NeuralKeyboard-debug-apk`).

จากนั้นเปิดแอป **NeuralKeyboard** → กด "เปิดใช้งาน" → "เปลี่ยนมาใช้" แล้วลองพิมพ์ในช่องทดสอบ

## อัปเดตแอป / Updating

ทุกครั้งที่ push, GitHub Actions จะสร้าง APK ใหม่ (`NeuralKeyboard-debug-apk-<เลขรอบ>`) โดย
- เซ็นด้วยกุญแจเดียวกันทุกครั้ง (`app/debug.keystore`) → ติดตั้งทับของเดิมได้เลย ไม่ต้องถอนการติดตั้ง
- `versionCode` = เลขรอบของ CI จึงสูงขึ้นทุกครั้ง
- คำที่แป้นพิมพ์เรียนรู้ไว้และการตั้งค่า **ยังอยู่ครบ** หลังอัปเดต

กุญแจนี้ใช้สำหรับทดสอบเท่านั้น ถ้าจะลง Play Store ต้องสร้างกุญแจ release ส่วนตัวเก็บใน GitHub Secrets

## ขั้นต่อไปที่แนะนำ / Next steps

1. **bigram ภาษาไทยจากคลังข้อความ** (ตอนนี้ bigram ภาษาไทยยังเป็นชุดที่เขียนเอง)
2. **Neural model บนเครื่อง** – เพิ่มโมเดลภาษาขนาดเล็ก (TensorFlow Lite / ONNX) เพื่อจัดอันดับคำแนะนำจากบริบททั้งประโยค โดยใช้ `SuggestionEngine` เป็นตัวสร้างคำผู้สมัคร
3. **Glide typing (ลากนิ้ว)**, emoji, ปุ่มกดค้างเพื่อพิมพ์ตัวเลข/สัญลักษณ์
4. **ตัดคำไทยด้วยพจนานุกรม (maximal matching)** แทนการตัดแบบคำยาวที่สุดตอนพิมพ์
