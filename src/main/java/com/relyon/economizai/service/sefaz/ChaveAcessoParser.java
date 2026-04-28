package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.InvalidQrPayloadException;
import com.relyon.economizai.model.enums.UnidadeFederativa;

import java.util.regex.Pattern;

public final class ChaveAcessoParser {

    private static final Pattern CHAVE_PATTERN = Pattern.compile("\\d{44}");
    private static final Pattern P_PARAM_PATTERN = Pattern.compile("[?&]p=([^&]+)", Pattern.CASE_INSENSITIVE);

    private ChaveAcessoParser() {}

    public static String extractChave(String qrPayload) {
        if (qrPayload == null || qrPayload.isBlank()) {
            throw new InvalidQrPayloadException();
        }
        var trimmed = qrPayload.trim();

        var pMatcher = P_PARAM_PATTERN.matcher(trimmed);
        if (pMatcher.find()) {
            var pValue = pMatcher.group(1);
            var head = pValue.split("\\|", 2)[0];
            if (CHAVE_PATTERN.matcher(head).matches()) {
                return head;
            }
        }

        var pipeHead = trimmed.split("\\|", 2)[0];
        if (CHAVE_PATTERN.matcher(pipeHead).matches()) {
            return pipeHead;
        }

        if (CHAVE_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }

        throw new InvalidQrPayloadException();
    }

    public static UnidadeFederativa extractUf(String chave) {
        if (chave == null || !CHAVE_PATTERN.matcher(chave).matches()) {
            throw new InvalidQrPayloadException();
        }
        var ufCode = Integer.parseInt(chave.substring(0, 2));
        try {
            return UnidadeFederativa.fromIbgeCode(ufCode);
        } catch (IllegalArgumentException ex) {
            throw new InvalidQrPayloadException();
        }
    }

    public static String extractCnpj(String chave) {
        if (chave == null || !CHAVE_PATTERN.matcher(chave).matches()) {
            throw new InvalidQrPayloadException();
        }
        return chave.substring(6, 20);
    }
}
