# WebView network isolation — repository research

This is research for the **unreleased** Koorix web reader. No third-party
code, dependency, service, installer, or additional translation model was
added or executed. None of these sources is proof that Koorix is safe.

## Candidates inspected

### Fauxx: WebView CONNECT proxy

- Repository: https://github.com/digital-grease/fauxx
- Merged PR: https://github.com/digital-grease/fauxx/pull/310
- Examined PR head: `d7960b45095ee26c60b29888b8d6493abda35797`.
- Repository license: **AGPL-3.0**. This review records ideas and observations;
  no code has been copied. Assess license obligations separately before reuse.
- Examined `LoopbackProxy.kt`, `WebViewProxyOverride.kt`,
  `CustomDnsRouter.kt`, `LoopbackProxyTest.kt`, and
  `CustomDnsInstrumentedTest.kt` at that revision. The project uses an
  authenticated loopback CONNECT proxy, checks resolved destinations, keeps
  TLS between WebView and the remote host, limits concurrent/unauthenticated
  work, and has socket and WebView tests. Its authors report testing specific
  WebView versions; those are their claims, not Koorix test results.
- **Not a drop-in security boundary for Koorix.** Its resolver/router is
  designed to fail open for its own DNS feature, whereas Koorix must not
  silently browse without any intended isolation. At this revision the proxy
  override configuration does not call `removeImplicitRules()`: according to
  the AndroidX API, localhost and link-local destinations can bypass the proxy
  by default. Its proxy implementation's own public-address checks therefore
  must not be mistaken for coverage of all WebView traffic.

### Home Assistant Android: CONNECT proxy proposal

- Repository: https://github.com/home-assistant/android (Apache-2.0).
- PR: https://github.com/home-assistant/android/pull/6906; examined head
  `4672f180ed43675602f5f9cfd70e199269be9167`. The PR was closed without a
  merge as observed during this review.
- Its objective is IPv6/DNS connectivity for Home Assistant, not blocking LAN
  access. It discusses CONNECT parsing, avoiding buffered reads that consume
  TLS bytes, main-thread/lifecycle handling, and tests. The examined override
  configuration also does not remove implicit local bypass rules. Do not
  treat it as an SSRF or DNS-rebinding defence.

### WebviewProxy

- Repository: https://github.com/sion-wang/WebviewProxy
- The GitHub metadata returned no SPDX license. It is an old proxy demo, not
  evidence of protected local-network isolation. Do not import its code without
  resolving licensing and security review.

## AndroidX / Chromium constraints

- AndroidX [`ProxyConfig.Builder`](https://developer.android.com/reference/androidx/webkit/ProxyConfig.Builder)
  says localhost and link-local addresses bypass proxy settings by default;
  `removeImplicitRules()` changes this behavior. `addDirect()`, bypass rules,
  a failed/unsupported override, and delayed activation can defeat a security
  boundary. [`ProxyController`](https://developer.android.com/reference/androidx/webkit/ProxyController)
  applies process-wide to WebViews and says to wait for its callback.
- Chromium's [local network access discussion](https://groups.google.com/a/chromium.org/g/blink-dev/c/D4nqAa3FUN8)
  says WebView unconditionally grants the web permission in that design; a
  separate Android app-level permission does not protect all older devices.
- `shouldInterceptRequest` is not a complete redirect/network interceptor.
  WebRTC/UDP, service workers, WebSockets, proxy failure, other WebViews and
  WebView-version differences all require explicit testing; proxy routing
  alone must not be advertised as a complete sandbox.

## Koorix next steps, not implemented

Design an independent, license-compatible, fail-closed solution with a
bounded authenticated local proxy, public-only destination checks on actual
resolved IP addresses, no automatic direct fallback, proper local-bypass
handling, and preserved end-to-end TLS. It must close safely during activity
lifecycle changes and not affect the existing normal translator/offline mode.
Run adversarial tests on the regular installed APK for IPv4/IPv6, DNS rebinding,
redirects, private/loopback addresses, iframes, service workers, WebSockets,
proxy startup/failure and disabled-feature paths. Verify with a public comic
page and real device where available. The current release block stays in place
until the actual feature and security acceptance are complete.
