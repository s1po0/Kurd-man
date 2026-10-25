package com.kurdman.app;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tokenizer for the Sorani OFFLINE language pack
 * (Helsinki-NLP/opus-mt-en-ine, quantized). Like the pack of every
 * other language it is a downloaded translation pack — no cloud, it
 * simply runs inside the phone.
 *
 * <p>Verified end-to-end in CI to match Hugging Face's MarianTokenizer
 * exactly: English text is segmented by {@code source.spm}, piece
 * strings are mapped to model ids through {@code vocab.json}, the
 * Sorani target is selected with the sentence-initial
 * {@code >>kur_Arab<<} token (Central Kurdish / Sorani, Arabic
 * script), {@code </s>}=0 is EOS and 61160 is PAD/decoder-start.</p>
 */
final class OpusTokenizer implements AutoCloseable {
    private static final String TAG = "OpusTokenizer";

    static final long EOS_ID = 0L;
    static final long UNK_ID = 1L;
    static final long PAD_ID = 61160L;
    private static final String SORANI_TAG = ">>kur_Arab<<";

    private SentencePieceBpe source;
    private Map<String, Integer> pieceToId;
    private String[] idToPiece;
    private long soraniTagId = -1L;
    private final Set<Integer> bannedIds = new HashSet<>();

    void initialize(File sourceSpmFile, File vocabJsonFile) throws IOException {
        close();
        SentencePieceBpe src = new SentencePieceBpe();
        src.loadModel(sourceSpmFile);
        JSONObject vocab;
        try {
            vocab = new JSONObject(readText(vocabJsonFile));
        } catch (Exception e) {
            src.close();
            throw new IOException("OFFLINE_AI_MODEL_MISMATCH: Sorani pack "
                    + "vocab.json is unreadable", e);
        }
        HashMap<String, Integer> map = new HashMap<>(1 << 17);
        int maxId = -1;
        Iterator<String> keys = vocab.keys();
        while (keys.hasNext()) {
            String piece = keys.next();
            int id = vocab.optInt(piece, -1);
            if (id >= 0) {
                map.put(piece, id);
                if (id > maxId) {
                    maxId = id;
                }
            }
        }
        Integer tagId = map.get(SORANI_TAG);
        if (tagId == null || maxId < 1000) {
            src.close();
            throw new IOException("OFFLINE_AI_MODEL_MISMATCH: Sorani pack is "
                    + "missing the " + SORANI_TAG + " language token");
        }
        String[] inverse = new String[maxId + 1];
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            inverse[entry.getValue()] = entry.getKey();
        }
        source = src;
        pieceToId = map;
        idToPiece = inverse;
        soraniTagId = tagId;

        // Tokens the decoder must never emit: PAD and every >>lang<< tag.
        bannedIds.clear();
        bannedIds.add((int) PAD_ID);
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            String piece = entry.getKey();
            if (piece.startsWith(">>") && piece.endsWith("<<")) {
                bannedIds.add(entry.getValue());
            }
        }
        Log.i(TAG, "Sorani pack tokenizer ready (vocab " + map.size()
                + ", tag id " + soraniTagId + ", banned "
                + bannedIds.size() + ")");
    }

    boolean isInitialized() {
        return source != null && pieceToId != null && soraniTagId >= 0;
    }

    /** {@code [>>kur_Arab<<] + vocab ids of BPE(English text) + [</s>]}. */
    long[] encodeSource(String englishText) {
        if (!isInitialized()) {
            throw new IllegalStateException("Sorani pack tokenizer is not ready");
        }
        List<String> pieces = source.encodePieces(
                englishText == null ? "" : englishText);
        long[] result = new long[pieces.size() + 2];
        result[0] = soraniTagId;
        for (int i = 0; i < pieces.size(); i++) {
            Integer id = pieceToId.get(pieces.get(i));
            result[i + 1] = id == null ? UNK_ID : id;
        }
        result[result.length - 1] = EOS_ID;
        return result;
    }

    int sourceTokenCount(String englishText) {
        if (!isInitialized()) {
            return englishText == null ? 0 : englishText.length();
        }
        return source.encodePieces(
                englishText == null ? "" : englishText).size() + 2;
    }

    String decodeTarget(long[] tokenIds) {
        if (!isInitialized() || tokenIds == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        ByteArrayOutputStream pendingBytes = new ByteArrayOutputStream();
        for (long id : tokenIds) {
            if (id <= EOS_ID || id >= idToPiece.length
                    || bannedIds.contains((int) id)) {
                continue;
            }
            String piece = idToPiece[(int) id];
            if (piece == null) {
                continue;
            }
            Integer byteValue = bytePieceValue(piece);
            if (byteValue != null) {
                pendingBytes.write(byteValue);
                continue;
            }
            flushPendingBytes(out, pendingBytes);
            out.append(piece);
        }
        flushPendingBytes(out, pendingBytes);
        return out.toString().replace('\u2581', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    boolean isBanned(long tokenId) {
        return tokenId < 0 || tokenId > Integer.MAX_VALUE
                || bannedIds.contains((int) tokenId);
    }

    @Override
    public void close() {
        if (source != null) {
            source.close();
            source = null;
        }
        pieceToId = null;
        idToPiece = null;
        soraniTagId = -1L;
    }

    private static Integer bytePieceValue(String piece) {
        if (piece == null || piece.length() != 6
                || !piece.startsWith("<0x") || !piece.endsWith(">")) {
            return null;
        }
        try {
            return Integer.parseInt(piece.substring(3, 5), 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void flushPendingBytes(
            StringBuilder out, ByteArrayOutputStream pendingBytes) {
        if (pendingBytes.size() == 0) {
            return;
        }
        out.append(new String(pendingBytes.toByteArray(), StandardCharsets.UTF_8));
        pendingBytes.reset();
    }

    private static String readText(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
