package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.model.enums.UnidadeFederativa;

import java.util.Set;

public interface SefazAdapter {

    /**
     * UFs this adapter can fetch + parse. Returning a set (not a single UF)
     * lets one adapter cover several states that share infrastructure — most
     * obvious case: the SVRS hub serves NFC-e for many states beyond RS, so
     * a single SVRS adapter can claim all of them.
     */
    Set<UnidadeFederativa> supportedStates();

    String fetchHtml(String qrPayload);

    ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl);
}
