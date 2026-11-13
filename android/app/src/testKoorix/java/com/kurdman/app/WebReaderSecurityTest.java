package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Native URL gate only; does not imply safety or compatibility for arbitrary sites. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class)
public class WebReaderSecurityTest {
    @Test public void acceptsOnlyHttpsWithoutCredentials() {
        assertTrue(WebReaderActivity.secureUrl("https://example.com/chapter?a=1"));
        assertFalse(WebReaderActivity.secureUrl("http://example.com/a"));
        assertFalse(WebReaderActivity.secureUrl("https://user:pass@example.com/a"));
        assertFalse(WebReaderActivity.secureUrl("file:///data/user/0/file"));
        assertFalse(WebReaderActivity.secureUrl("javascript:alert(1)"));
        assertFalse(WebReaderActivity.secureUrl(null));
    }
    @Test public void onlyMatchesExactOriginAndPort() {
        String page="https://example.com/chapter";
        assertTrue(WebReaderActivity.sameOrigin(page,"https://EXAMPLE.COM:443/page.png"));
        assertFalse(WebReaderActivity.sameOrigin(page,"https://cdn.example.com/page.png"));
        assertFalse(WebReaderActivity.sameOrigin(page,"https://example.com.evil.test/page.png"));
        assertFalse(WebReaderActivity.sameOrigin(page,"https://example.com:444/page.png"));
        assertFalse(WebReaderActivity.sameOrigin(page,"http://example.com/page.png"));
        assertFalse(WebReaderActivity.sameOrigin(page,"https://evil.test@example.com/page.png"));
    }
}
