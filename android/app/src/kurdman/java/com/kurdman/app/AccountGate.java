package com.kurdman.app;

import android.app.Activity;

/**
 * Personal KurdMan build: there is no account system and no usage
 * limit, so every gate simply lets the action through. The Koorix
 * public edition ships a real implementation of this same facade in
 * its own flavour source set.
 */
public final class AccountGate {

    private AccountGate() {
    }

    public static void enforceOnLaunch(Activity activity) {
        // No sign-in required in the personal build.
    }

    public static void guardJobStart(Activity activity, int pages,
                                     Runnable onAllowed) {
        if (onAllowed != null) {
            onAllowed.run();
        }
    }

    public static void openAccount(Activity activity) {
        // No account screen in the personal build.
    }
}
