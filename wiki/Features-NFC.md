# NFC / RFID

> Public reference: PN532 and tag-type limits; writing/erasing changes the tag. Presence, successful replies and physical effects are
> separate claims. Check [revision-specific evidence](https://github.com/1vcian/Brucia/blob/main/TESTS.md)
> and [Tutorials](Tutorials.md) before relying on a workflow.

Every NFC/RFID tool, driven by the onboard PN532. Gated on `caps.nfc`.

> For authorized security research and education only. The dictionary attack and
> magic-UID write are dual-use - only use them on tags you own or are explicitly
> permitted to test.

## Read / write / emulate / clone / info / save / erase
The core tag operations: read a tag's data, write it, emulate a saved tag, clone one tag
to another, show tag info, save a read to the phone, and erase a writable tag.

## Dictionary attack
A MIFARE Classic key-dictionary check runs on the device (`mifare_keycheck`), backed by a
phone-side dictionary of 65+ known default keys plus Crypto-1 / Ultralight / NTAG analysis
and a clone-risk assessment. Authorized testing only.

## NDEF
An NDEF builder on the phone, plus **NDEF push**: emulate a URL/text/contact NDEF tag to
another phone (`nfc_ndef_push`).
**Write NDEF URL** writes a tag and requires confirmation; it is not a read operation.
Erase also changes the tag. Use a disposable supported tag for acceptance checks.

## EMV read
Reads supported EMV information (`nfc_emv_read`) and presents masked card details.
Masking the output is not a claim that the reader never processes the underlying data.

## Magic-UID write
Sets the UID on a magic / Gen1A MIFARE card (`nfc_write_uid`). Authorized testing only.

## SRIX / ST25TB read
Reads an ISO 14443-B SRIX/ST25TB tag via the onboard PN532 (`srix_read`), returning the UID
and the full 512-byte dump.

## External-device only: Chameleon, amiibo, nested, mfkey32
Chameleon/amiibo emulation and MIFARE nested / mfkey32 key recovery need extra hardware
Brucia does not have on board (ChameleonUltra, Amiibolink, PN532Killer). The app reports
this honestly rather than pretending to crack keys the onboard PN532 cannot; use the
on-board dictionary key-check above instead.

---
Back to [Features](Features.md).
