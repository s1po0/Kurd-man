package com.kurdman.app;

/** Pure, testable ordering of checks that MUST precede any entitlement spending. */
final class TranslationPreflight {
    enum Error { NONE, BUSY, NO_INPUT, SOURCE_MISSING, UNSUPPORTED_PAIR, PACK_MISSING }
    static Error check(boolean busy,boolean input,boolean sourcesPresent,boolean supportedPair,boolean offline,boolean packReady) {
        if(busy)return Error.BUSY;
        if(!input)return Error.NO_INPUT;
        if(!sourcesPresent)return Error.SOURCE_MISSING;
        if(!supportedPair)return Error.UNSUPPORTED_PAIR;
        if(offline&&!packReady)return Error.PACK_MISSING;
        return Error.NONE;
    }
}
