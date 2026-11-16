# WebView isolation work — not release acceptance

The existing fail-closed release gate remains. `PublicNetworkGuard` is an
unconnected transport primitive, not a WebView firewall. The browser still
uses its old WebView and still has the security blockers in
`docs/KOORIX_WEB_READER.md`; no 1.8.4 distribution is permitted on this basis.

## Address and DNS primitive

`PublicNetworkGuard` rejects private, loopback, link-local, multicast,
documentation, benchmark, 6to4 and other selected ambiguous IP ranges. It
rejects the entire DNS response if any address is non-public, then connects
only to a numeric address from that validated answer, rather than resolving a
hostname a second time. Direct sockets must use `Proxy.NO_PROXY`. JVM tests
cover mixed answers, selected IPv4/IPv6 specials, a synthetic rebinding resolver,
timeouts and actual-vs-validated endpoint mismatch. Conservatively rejecting
special/reserved addresses may make some public hosts inaccessible.

Run `43773141385` on `2821887d1160fbbaef1624bf67c6575741cdb7ce` passed
315 Android/JVM tests and the existing signed regular-APK synthetic checks.
These checks did not route WebView through this primitive or test real
DNS-rebinding/WebView behavior on a device.

This is not a complete IANA allocation registry, a proof against unusual
routing/NAT, a TLS client, a redirect policy or a proxy. Do not connect it to
the existing WebView and claim protection: WebView can initiate traffic by
other paths. A future transport must validate every redirect and every actual
connection, authenticate the original host with TLS without changing the Host
or SNI, enforce bounded concurrency/time/bytes, and never fall back to direct
unvalidated traffic. System proxies, DNS64/NAT64 and unusual routes require
explicit threat-model decisions and tests.

## Experimental HTTPS client (not integrated)

`PinnedHttpsClient` is a preliminary, unused GET-only transport; it does not
route WebView traffic or fix the current browser. It rejects non-HTTPS URLs,
redirects, ambiguous HTTP framing and compression, pins validated DNS answers,
and bounds response bytes. It still needs an overall deadline including DNS,
robust concurrency limits, fuller framing/compatibility review, independent
security review and an actual isolation architecture before any use.

Hosted OpenJDK probe on `754b427` passed a real public GET and rejected a
wrong TLS hostname, after an earlier attempt incorrectly used the default
`HttpsURLConnection` verifier as a standalone validator (which failed closed).
320 JVM tests passed there, but installed APK run `43775129068` failed later
with `TimeoutError: Missing control webReaderTranslate`; not a clean build.
Disposable Android API34 probe work followed: runs `43776126336`,
`43776688999` and `43777810131` should not be treated as passed end-to-end;
the first two probes timed out and the third run's final outcome could not be
retrieved due to GitHub API rate limiting. In run `43776688999`, diagnostic
stages show the positive GET completed and the wrong-host phase started, but
not its result. An app_process lifecycle termination fix was added afterward;
it needs a verified run. Host-provider behavior cannot establish Android or
WebView isolation. The release gate remains in force.

## Isolated script execution candidate

AndroidX WebKit [release notes](https://developer.android.com/jetpack/androidx/releases/webkit)
describe APIs for isolated JavaScript execution worlds in 1.16.0, and
[`WebViewCompat`](https://developer.android.com/reference/androidx/webkit/WebViewCompat)
provides `addJavaScriptOnEvent`, isolated `addWebMessageListener`, and
`JavaScriptReplyProxy.executeJavaScript` when
[`JS_INJECTION_IN_FRAME_AND_WORLD`](https://developer.android.com/reference/androidx/webkit/WebViewFeature#JS_INJECTION_IN_FRAME_AND_WORLD())
is supported by the installed WebView. These are potential protection against
page JavaScript replacing injected globals, not proof of isolation from a
hostile DOM, spoofed content, network attacks, or CSP/site-protection concerns.
No AndroidX dependency or new bridge was added here. A future implementation
must fail closed on unsupported WebViews, bind replies to the active main frame
and navigation, enforce strict origin/size/sequence limits, test hostile frames
and navigation races, and must not use isolated execution to bypass access
controls or page protections. Compatibility and behavior must be tested on
actual supported Android/WebView versions, not inferred from API existence.

## Open acceptance work

WebView process-wide proxy override (if used) must be active before any load,
remove implicit localhost bypass, reject unsupported override and proxy failure,
never use `addDirect`, and cover connections from iframes, redirects, workers,
WebSockets and UDP/WebRTC/QUIC or be proven unable to reach those channels.
Proxy loopback access itself must be authenticated and bounded; lifecycle and
other WebViews in the process must not cause a silent bypass. Pinned socket
checks do not prove proxy completeness. An alternative sandboxed/static browser
would have different functionality and also needs explicit supported-site and
security validation. Captured image bytes and DOM state remain untrusted even
when an isolated JavaScript world is available.
