package com.kurdman.app;

import android.app.Activity;
import android.content.Intent;

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

    public static void guardJobStart(Activity a,int pages,boolean offline,String id,Runnable allowed,Runnable blocked) { allowed.run(); }
    public interface Decision { void ready(boolean allowed,String reason); }
    public static void authorize(android.content.Context context,TranslationProject p,Decision result) { result.ready(true,""); }
    public static void prepareOfflineAllowance(android.content.Context context) { }
    public static String identity(android.content.Context context) { return "personal"; }
    public static boolean isJobApproved(android.content.Context context,TranslationProject project) { return true; }
    public static void jobFinished(android.content.Context context,TranslationProject project) { }

    public static void openAccount(Activity activity) {
        // No account screen in the personal build.
    }

    public static boolean handleAuthDeepLink(Activity activity, Intent intent) {
        return false;
    }
}
