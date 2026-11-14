# Multiple CBZ input (unreleased)

The existing single `GET_CONTENT */*` chooser now accepts multiple CBZ/ZIP
files together. File names use natural numeric order (chapter 2 before chapter
10), and each archive's supported image pages use natural page-name order.
They are processed together as **one project** and one combined CBZ result;
this is not separate projects per chapter. Selecting CBZ files and separate
images in the same batch is still rejected with a localized explanation.
A provider that cannot return multiple selected URIs cannot enable multi-select.

Only selected URIs are copied into private temporary storage. The combined
input is bounded to 300 pages, 300 MB compressed input/output and 1 GB expanded
content, with a 150 MB page cap. Images are streamed to numbered ZIP entries,
not extracted using untrusted paths. A failed or canceled import does not
replace the previous selection; temporary files are removed. Original selected
files and existing projects are not deleted.

## Evidence

- `43616279737` at `946294f`: native/JVM tests, same-key regular APK build and
  Android34 installed synthetic checks passed. Unit coverage includes page
  order, ZIP path handling, malformed/empty archives, page limit, cancellation,
  existing-file preservation, actual chooser callback and durable project
  preparation. These are synthetic cases, not all archive providers or phones.
- Final localized hint build `724fef4`: workflow `43618122825` passed
  the native/JVM suite, signed regular APK verification, and its existing
  installed-APK synthetic checks. This workflow does not itself exercise the
  multiple-CBZ chooser.
- Separate direct observe→act→observe session `43617326391`, check
  `132516049977`, launch commit `63eadbf98cd71481b7c8e0fc0109666871005672`,
  Android34 official emulator with ARM64 bridge; regular candidate 1.8.4 APK
  SHA256 `1104232a41e37114760030352eec597d2d76036b703e079872713d6a6d989667`.
  Two authored synthetic CBZs were selected through the real system chooser;
  frame 3 blob `d3a65a62f35ec67c5aee2298ee1b1e2f68e4f148` shows both selected,
  frame 4 `13dce9ea15377bef2a4e514f672f5c57174f536d` shows two pages ready,
  frame 6 `c6500a2a91a4a4a6993968e3cdf66617b5d9be65` shows completed job,
  and frame 8 `f95c8de058ac027b30ea0479fa241aa7945133a6` shows both page
  sections in the result. The two synthetic images were identical; this run
  does not independently establish page ordering or translated meaning. The
  session was ended explicitly with a neutral, non-verdict conclusion.
- First direct attempt `43616922177` failed while setting up synthetic files;
  it produced no app observations and was not counted as a pass.

No physical Samsung device or other file manager was tested. The unreleased
candidate still contains the previously documented web-reader release blocker;
no unsafe APK was published or delivered. A green CBZ test does not resolve
browser network isolation or general translation quality.
