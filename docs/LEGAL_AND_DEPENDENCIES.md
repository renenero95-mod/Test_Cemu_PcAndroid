# Legal, Security, and Dependency Policy

## 1. User-provided content

The app processes user-provided game data. Do not distribute game images, copyrighted assets, proprietary console firmware, console keys, or proprietary SDK components.

## 2. Security boundary

Imported binary files are untrusted:
- bounds-check every offset and length;
- cap decompression ratios and output sizes;
- avoid unsafe deserialization;
- isolate parsers where practical;
- do not execute imported code during analysis;
- validate generated packages before runtime launch.

## 3. DRM and platform security

Do not implement DRM circumvention, key extraction, firmware dumping, or security bypass. If a proposed workflow depends on these, stop and request a lawful alternative.

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
