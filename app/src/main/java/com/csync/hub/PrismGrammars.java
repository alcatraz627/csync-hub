package com.csync.hub;

import io.noties.prism4j.annotations.PrismBundle;

/** Bundles Prism4j grammars for code syntax highlighting; the processor
 *  generates com.csync.hub.GrammarLocator from this. */
@PrismBundle(includeAll = true)
public final class PrismGrammars {
    private PrismGrammars() {}
}
