package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.model.enums.UnidadeFederativa;

public interface SefazAdapter {

    UnidadeFederativa supportedState();

    String fetchHtml(String qrPayload);

    ParsedReceipt parseHtml(String html, String chaveAcesso, String sourceUrl);
}
