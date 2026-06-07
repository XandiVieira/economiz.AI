package com.relyon.economizai.model.enums;

/**
 * Business segment of a merchant, verified from its CNPJ's CNAE (economic
 * activity) via an external registry. Drives category fallbacks — e.g. an
 * unrecognized item at a PHARMACY merchant defaults to ProductCategory.HEALTH.
 */
public enum MerchantSegment {
    UNKNOWN,      // not classified yet (or lookup failed)
    SUPERMARKET,  // CNAE 4711* / 4712* (hiper/super/minimercados)
    PHARMACY,     // CNAE 4771* (produtos farmacêuticos)
    OTHER         // classified, but neither supermarket nor pharmacy
}
