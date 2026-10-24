package com.kurdman.app;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * NLLB-200 wrapper around {@link SentencePieceBpe}.
 *
 * <p>Language-token IDs are computed from the actual SentencePiece
 * vocabulary size in the downloaded file:
 * {@code sentencePieceVocabSize + 1 + fairseqLanguageIndex}. For the pinned
 * distilled-600M tokenizer this gives Central Kurdish {@code ckb_Arab}
 * token ID 256038 when the SentencePiece vocabulary has 256,000 pieces.</p>
 */
final class NllbTokenizer implements AutoCloseable {
    private static final String TAG = "NllbTokenizer";

    static final long EOS_TOKEN_ID = 2L;
    private static final long UNK_MODEL_ID = 3L;
    private static final int FAIRSEQ_OFFSET = 1;
    private static final String TARGET_LANGUAGE = "ckb_Arab";
    private static final int MODEL_VOCAB_SIZE = 256206;

    /**
     * The canonical fairseq ordering of all 202 NLLB language tokens.
     * Do not reorder: each index is part of the model token ID.
     */
    private static final String[] FAIRSEQ_LANGUAGE_CODES = {
            "ace_Arab", "ace_Latn", "acm_Arab", "acq_Arab", "aeb_Arab",
            "afr_Latn", "ajp_Arab", "aka_Latn", "amh_Ethi", "apc_Arab",
            "arb_Arab", "ars_Arab", "ary_Arab", "arz_Arab", "asm_Beng",
            "ast_Latn", "awa_Deva", "ayr_Latn", "azb_Arab", "azj_Latn",
            "bak_Cyrl", "bam_Latn", "ban_Latn", "bel_Cyrl", "bem_Latn",
            "ben_Beng", "bho_Deva", "bjn_Arab", "bjn_Latn", "bod_Tibt",
            "bos_Latn", "bug_Latn", "bul_Cyrl", "cat_Latn", "ceb_Latn",
            "ces_Latn", "cjk_Latn", "ckb_Arab", "crh_Latn", "cym_Latn",
            "dan_Latn", "deu_Latn", "dik_Latn", "dyu_Latn", "dzo_Tibt",
            "ell_Grek", "eng_Latn", "epo_Latn", "est_Latn", "eus_Latn",
            "ewe_Latn", "fao_Latn", "pes_Arab", "fij_Latn", "fin_Latn",
            "fon_Latn", "fra_Latn", "fur_Latn", "fuv_Latn", "gla_Latn",
            "gle_Latn", "glg_Latn", "grn_Latn", "guj_Gujr", "hat_Latn",
            "hau_Latn", "heb_Hebr", "hin_Deva", "hne_Deva", "hrv_Latn",
            "hun_Latn", "hye_Armn", "ibo_Latn", "ilo_Latn", "ind_Latn",
            "isl_Latn", "ita_Latn", "jav_Latn", "jpn_Jpan", "kab_Latn",
            "kac_Latn", "kam_Latn", "kan_Knda", "kas_Arab", "kas_Deva",
            "kat_Geor", "knc_Arab", "knc_Latn", "kaz_Cyrl", "kbp_Latn",
            "kea_Latn", "khm_Khmr", "kik_Latn", "kin_Latn", "kir_Cyrl",
            "kmb_Latn", "kon_Latn", "kor_Hang", "kmr_Latn", "lao_Laoo",
            "lvs_Latn", "lij_Latn", "lim_Latn", "lin_Latn", "lit_Latn",
            "lmo_Latn", "ltg_Latn", "ltz_Latn", "lua_Latn", "lug_Latn",
            "luo_Latn", "lus_Latn", "mag_Deva", "mai_Deva", "mal_Mlym",
            "mar_Deva", "min_Latn", "mkd_Cyrl", "plt_Latn", "mlt_Latn",
            "mni_Beng", "khk_Cyrl", "mos_Latn", "mri_Latn", "zsm_Latn",
            "mya_Mymr", "nld_Latn", "nno_Latn", "nob_Latn", "npi_Deva",
            "nso_Latn", "nus_Latn", "nya_Latn", "oci_Latn", "gaz_Latn",
            "ory_Orya", "pag_Latn", "pan_Guru", "pap_Latn", "pol_Latn",
            "por_Latn", "prs_Arab", "pbt_Arab", "quy_Latn", "ron_Latn",
            "run_Latn", "rus_Cyrl", "sag_Latn", "san_Deva", "sat_Beng",
            "scn_Latn", "shn_Mymr", "sin_Sinh", "slk_Latn", "slv_Latn",
            "smo_Latn", "sna_Latn", "snd_Arab", "som_Latn", "sot_Latn",
            "spa_Latn", "als_Latn", "srd_Latn", "srp_Cyrl", "ssw_Latn",
            "sun_Latn", "swe_Latn", "swh_Latn", "szl_Latn", "tam_Taml",
            "tat_Cyrl", "tel_Telu", "tgk_Cyrl", "tgl_Latn", "tha_Thai",
            "tir_Ethi", "taq_Latn", "taq_Tfng", "tpi_Latn", "tsn_Latn",
            "tso_Latn", "tuk_Latn", "tum_Latn", "tur_Latn", "twi_Latn",
            "tzm_Tfng", "uig_Arab", "ukr_Cyrl", "umb_Latn", "urd_Arab",
            "uzn_Latn", "vec_Latn", "vie_Latn", "war_Latn", "wol_Latn",
            "xho_Latn", "ydd_Hebr", "yor_Latn", "yue_Hant", "zho_Hans",
            "zho_Hant", "zul_Latn"
    };

    private SentencePieceBpe sentencePiece;
    private final Map<String, Long> languageTokenIds = new HashMap<>();
    private long firstLanguageTokenId = -1;
    private long lastLanguageTokenId = -1;

    void initialize(File sentencePieceFile) throws IOException {
        close();
        SentencePieceBpe loaded = new SentencePieceBpe();
        loaded.loadModel(sentencePieceFile);
        sentencePiece = loaded;
        computeLanguageTokenIds(loaded.vocabSize());
        Log.i(TAG, "Tokenizer ready: spVocab=" + loaded.vocabSize()
                + ", ckb_Arab=" + languageTokenIds.get(TARGET_LANGUAGE));
    }

    boolean isInitialized() {
        return sentencePiece != null && sentencePiece.isLoaded();
    }

    int tokenCount(String text) {
        ensureInitialized();
        return sentencePiece.encode(text).length;
    }

    long[] encode(String text, String appSourceLanguage) {
        ensureInitialized();
        Long sourceToken = languageTokenIds.get(toNllbCode(appSourceLanguage));
        if (sourceToken == null) {
            throw new IllegalArgumentException(
                    "Unsupported offline source language: " + appSourceLanguage);
        }
        int[] spmIds = sentencePiece.encode(text);
        long[] modelIds = new long[spmIds.length + 2];
        modelIds[0] = sourceToken;
        for (int i = 0; i < spmIds.length; i++) {
            int spmId = spmIds[i];
            modelIds[i + 1] = spmId == 0
                    ? UNK_MODEL_ID
                    : (long) spmId + FAIRSEQ_OFFSET;
        }
        modelIds[modelIds.length - 1] = EOS_TOKEN_ID;
        return modelIds;
    }

    String decode(long[] modelIds) {
        ensureInitialized();
        if (modelIds == null || modelIds.length == 0) {
            return "";
        }
        ArrayList<Integer> spmIds = new ArrayList<>(modelIds.length);
        for (long modelId : modelIds) {
            if (modelId <= UNK_MODEL_ID || isLanguageToken(modelId)) {
                continue;
            }
            long spmId = modelId - FAIRSEQ_OFFSET;
            if (spmId < 0 || spmId >= sentencePiece.vocabSize()) {
                continue;
            }
            spmIds.add((int) spmId);
        }
        int[] ids = new int[spmIds.size()];
        for (int i = 0; i < spmIds.size(); i++) {
            ids[i] = spmIds.get(i);
        }
        return sentencePiece.decode(ids);
    }

    long targetLanguageTokenId() {
        ensureInitialized();
        Long id = languageTokenIds.get(TARGET_LANGUAGE);
        if (id == null) {
            throw new IllegalStateException("Missing ckb_Arab language token");
        }
        return id;
    }

    boolean isLanguageToken(long modelId) {
        return firstLanguageTokenId >= 0
                && modelId >= firstLanguageTokenId
                && modelId <= lastLanguageTokenId;
    }

    /** IDs at/after the language-token block are controls, never text. */
    boolean isNonTextToken(long modelId) {
        return firstLanguageTokenId >= 0 && modelId >= firstLanguageTokenId;
    }

    static String toNllbCode(String appSourceLanguage) {
        if ("ko".equals(appSourceLanguage)) {
            return "kor_Hang";
        }
        if ("ja".equals(appSourceLanguage)) {
            return "jpn_Jpan";
        }
        if ("zh-TW".equals(appSourceLanguage) || "zh-Hant".equals(appSourceLanguage)) {
            return "zho_Hant";
        }
        if ("zh-CN".equals(appSourceLanguage) || "zh-Hans".equals(appSourceLanguage)
                || "zh".equals(appSourceLanguage)) {
            return "zho_Hans";
        }
        if ("ckb".equals(appSourceLanguage)) {
            return TARGET_LANGUAGE;
        }
        // The app's default source is English.
        return "eng_Latn";
    }

    @Override
    public void close() {
        if (sentencePiece != null) {
            sentencePiece.close();
            sentencePiece = null;
        }
        languageTokenIds.clear();
        firstLanguageTokenId = -1;
        lastLanguageTokenId = -1;
    }

    private void computeLanguageTokenIds(int sentencePieceVocabSize) throws IOException {
        languageTokenIds.clear();
        if (FAIRSEQ_LANGUAGE_CODES.length != 202) {
            throw new IOException("Invalid NLLB language table: "
                    + FAIRSEQ_LANGUAGE_CODES.length);
        }
        long base = (long) sentencePieceVocabSize + FAIRSEQ_OFFSET;
        long last = base + FAIRSEQ_LANGUAGE_CODES.length - 1;
        if (sentencePieceVocabSize < 100000 || last >= MODEL_VOCAB_SIZE) {
            throw new IOException("Unexpected NLLB tokenizer vocabulary size: "
                    + sentencePieceVocabSize);
        }
        firstLanguageTokenId = base;
        lastLanguageTokenId = last;
        for (int i = 0; i < FAIRSEQ_LANGUAGE_CODES.length; i++) {
            long id = base + i;
            languageTokenIds.put(FAIRSEQ_LANGUAGE_CODES[i], id);
        }
    }

    private void ensureInitialized() {
        if (!isInitialized()) {
            throw new IllegalStateException("NLLB tokenizer is not initialized");
        }
    }
}
