# Legal, Security, and Dependency Policy

## 1. User-provided content

The app processes user-provided game data. Do not distribute game images, copyrighted assets, proprietary console firmware, console keys, or proprietary SDK components.

## 2. Security boundary

Imported binary files are untrusted. Static analysis is a supported core workflow and is distinct from executing imported code:
- XEX header/metadata inspection, hashing, structural parsing, section-table analysis, bounds validation, and disassembly are allowed without executing the file.
- Do not launch imported XEX files during analysis. Runtime execution is separate and must never happen implicitly as a parser side effect.
- A retail encrypted section may be marked opaque/unavailable; this does not prevent parsing readable headers or continuing unrelated work.
- The repository may reject committed game binaries for size/copyright hygiene; that restriction does not apply to a user selecting an external file for local analysis.
- bounds-check every offset and length;
- cap decompression ratios and output sizes;
- avoid unsafe deserialization;
- isolate parsers where practical;
- do not execute imported code during analysis;
- validate generated packages before runtime launch.

## 3. DRM and platform security

Do not implement DRM circumvention, key extraction, firmware dumping, or security bypass. If a specific requested operation actually depends on these, isolate that operation and propose a safe alternative. Do not stop static parsing, metadata analysis, hashing, tests, or unrelated project development merely because XEX/DRM/encryption is mentioned.

## 4. Third-party code

Before adopting a library or project:
- verify official repository and provenance;
- inspect current license and notices;
- identify copyleft/attribution/source-disclosure obligations;
- verify Android/ARM64 compatibility;
- pin a known version or commit;
- record modifications and patches;
- include required notices in distribution.

Do not assume a project is reusable merely because its source is public.

## 5. Dependency inventory

Maintain a machine-readable inventory with:
- name;
- source URL;
- version/commit;
- license;
- purpose;
- target platforms;
- modifications;
- build instructions;
- known restrictions.

No dependency may be marked approved until its license and build feasibility are reviewed.
