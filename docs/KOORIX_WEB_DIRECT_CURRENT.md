# Direct inspection of the current regular APK (unreleased)

This is an actual native **observe, choose one UI gesture, observe** session,
not an automated acceptance verdict. Run `43560840799`, check `132344663695`
(neutral after the session was explicitly finished); launch commit
`81fcae36b49446bfba78be9b2ec9111920201451`. Official API34 emulator,
`com.koorix.app` 1.8.4/code20, independently verified permanent v2 signer,
APK SHA256 `2a3bbd7c327ea54fd286b181951d3c59ba625fbed33f4165f4c52c13ab2c0840`.
The candidate was not distributed. Frames are real screenshots, not continuous
video. No user page or account was used. The public CC-BY comic was
[Pepper&Carrot Episode 1](https://www.peppercarrot.com/en/webcomic/ep01_Potion-of-Flight.html)
by David Revoy; displayed comic pixels are attributed to him under CC BY 4.0.

## What was observed

- Frames 0–6: agent navigated from the normal Koorix interface to its browser
  and loaded the public comic with the fixed test URL. Frames 7–9: scrolled and
  saw three comic story pages; entered Translate at the scrolled position.
- Frame 10: capture returned to the job and a notice reported **three images
  skipped**. The notice conflates filtered thumbnails and failed image loads;
  it does not establish which ones were skipped. Frame 12 showed a completed
  job warning that some text was retained. The reader displayed 3 captured
  images; frames 14, 15 and 17 show the three expected story pages at least in
  part. The separate header/end images are not independently established.
  The earlier five-image assertion was an unverified assumption, not evidence
  that exactly two story pages were lost.
- Frames 13/14: actual translated and original first page compared. The
  translated result contains stray large numbers `18` on artwork, apparent
  erasure artefacts, and a stray mixed-Latin/Sorani fragment outside the
  second balloon. The translated second balloon is not an acceptable clear
  rendering of its source. Frame 16 versus 15: extraneous `48` appears over
  the art; source art contains small numbers, not the large replacements.
  Frame 17: the comic credit/date area was processed into distorted text and an
  altered date. These are observed defects, not a general OCR diagnosis.
- Both original/translated toggle and page navigation responded. No explicit
  save was pressed. Frame observations are same-agent review, not a native
  human Sorani assessment or physical-phone proof. The site did not establish
  private-network isolation, security, general site support, or full meanings.

## Key screenshot blobs

The direct-session client verified PNG SHA256 and Git blob SHA1 on download.
Public artwork screenshots below are not user data (attribution above):

| Frame | Scope | Git blob |
|---|---|---|
| 6 | Public comic in actual browser | `48eb153a8d0d3db25fac4e0ea2232cb485bc5b93` |
| 10 | capture handoff and skipped-images notice | `f7362b1c085cd853a91d05d058ef4f32c93e4a57` |
| 13 | translated story page 1 | `8cacdcb452a4ec6742ff2b706d62634d523d0552` |
| 14 | original story page 1 | `95a550c911a18ed3417fe0564a70889ece3b907f` |
| 15 | original story page 2 | `6cbe1ac2f3213ed3ef1b1e67b2ca47843f568cc0` |
| 16 | translated story page 2 | `f6be99af44437773dba8e86ee1197a99c67bf6f8` |
| 17 | translated story page 3 | `9cc8651310673bde49667857f00cef143c852c9e` |

The visual defects, uncertain image filtering and unresolved network security
are release blockers. No APK or Telegram completion message was sent.
