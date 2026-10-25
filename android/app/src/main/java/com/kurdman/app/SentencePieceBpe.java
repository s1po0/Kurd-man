package com.kurdman.app;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java SentencePiece BPE tokenizer for the NLLB-200 model.
 *
 * <p>The protobuf/BPE approach is adapted from the MIT-licensed
 * InstantVoiceTranslate project (see assets/licenses). This implementation
 * works on Unicode code points instead of UTF-16 code units, applies an
 * NFKC/whitespace normalization pass, and supports SentencePiece byte
 * fallback pieces when the vocabulary contains them.</p>
 */
final class SentencePieceBpe implements AutoCloseable {
    private static final String TAG = "SentencePieceBpe";
    private static final String SPACE_MARKER = "\u2581";

    private static final class ParsedPiece {
        final String piece;
        final float score;
        final int type;

        ParsedPiece(String piece, float score, int type) {
            this.piece = piece;
            this.score = score;
            this.type = type;
        }
    }

    private String[] pieces = new String[0];
    private float[] scores = new float[0];
    private byte[] types = new byte[0];
    private Map<String, Integer> pieceToId = new HashMap<>();
    private int unkId;
    private boolean byteFallbackAvailable;

    int vocabSize() {
        return pieces.length;
    }

    boolean isLoaded() {
        return pieces.length > 0;
    }

    void loadModel(File modelFile) throws IOException {
        if (modelFile == null || !modelFile.isFile()) {
            throw new IOException("SentencePiece model not found: " + modelFile);
        }
        byte[] bytes = readAll(modelFile);
        List<ParsedPiece> parsed = parseModelProto(bytes);
        if (parsed.size() < 1000) {
            throw new IOException("Invalid SentencePiece model: only "
                    + parsed.size() + " pieces");
        }

        String[] newPieces = new String[parsed.size()];
        float[] newScores = new float[parsed.size()];
        byte[] newTypes = new byte[parsed.size()];
        Map<String, Integer> newPieceToId = new HashMap<>(1 << 19);
        for (int i = 0; i < parsed.size(); i++) {
            ParsedPiece piece = parsed.get(i);
            newPieces[i] = piece.piece;
            newScores[i] = piece.score;
            newTypes[i] = (byte) piece.type;
            // The first occurrence defines the canonical ID for a piece.
            newPieceToId.putIfAbsent(piece.piece, i);
        }

        pieces = newPieces;
        scores = newScores;
        types = newTypes;
        pieceToId = newPieceToId;
        Integer unk = pieceToId.get("<unk>");
        unkId = unk == null ? 0 : unk;
        byteFallbackAvailable = hasAllBytePieces();
        Log.i(TAG, "Loaded " + pieces.length + " SentencePiece pieces"
                + " (byteFallback=" + byteFallbackAvailable + ")");
    }

    int[] encode(String text) {
        ensureLoaded();
        if (text == null || text.trim().isEmpty()) {
            return new int[0];
        }

        String normalized = normalizeText(text);
        if (normalized.isEmpty()) {
            return new int[0];
        }

        // SentencePiece's dummy prefix and whitespace marker.
        String marked = SPACE_MARKER + normalized.replace(" ", SPACE_MARKER);
        List<String> symbols = new ArrayList<>();
        for (int offset = 0; offset < marked.length();) {
            int codePoint = marked.codePointAt(offset);
            symbols.add(new String(Character.toChars(codePoint)));
            offset += Character.charCount(codePoint);
        }

        // Iteratively merge the highest-scoring adjacent BPE pair.
        while (symbols.size() > 1) {
            float bestScore = Float.NEGATIVE_INFINITY;
            int bestIndex = -1;
            String bestMerged = null;
            for (int i = 0; i + 1 < symbols.size(); i++) {
                String merged = symbols.get(i) + symbols.get(i + 1);
                Integer id = pieceToId.get(merged);
                if (id != null && scores[id] > bestScore) {
                    bestScore = scores[id];
                    bestIndex = i;
                    bestMerged = merged;
                }
            }
            if (bestIndex < 0) {
                break;
            }
            symbols.set(bestIndex, bestMerged);
            symbols.remove(bestIndex + 1);
        }

        ArrayList<Integer> ids = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            Integer id = pieceToId.get(symbol);
            if (id != null) {
                ids.add(id);
            } else if (byteFallbackAvailable && appendByteFallback(symbol, ids)) {
                // appendByteFallback added one ID per UTF-8 byte.
            } else {
                ids.add(unkId);
            }
        }

        int[] result = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            result[i] = ids.get(i);
        }
        return result;
    }

    String decode(int[] ids) {
        ensureLoaded();
        if (ids == null || ids.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        ByteArrayOutputStream pendingBytes = new ByteArrayOutputStream();
        for (int id : ids) {
            if (id < 0 || id >= pieces.length) {
                continue;
            }
            String piece = pieces[id];
            Integer byteValue = bytePieceValue(piece);
            if (byteValue != null) {
                pendingBytes.write(byteValue);
                continue;
            }
            flushPendingBytes(out, pendingBytes);
            out.append(piece);
        }
        flushPendingBytes(out, pendingBytes);
        return out.toString().replace(SPACE_MARKER, " ").trim();
    }

    @Override
    public void close() {
        pieces = new String[0];
        scores = new float[0];
        types = new byte[0];
        pieceToId = new HashMap<>();
        byteFallbackAvailable = false;
    }

    private String normalizeText(String text) {
        // SentencePiece models use a precompiled normalizer. NFKC plus
        // whitespace collapsing is a practical Android approximation and
        // handles full-width Latin/CJK punctuation from OCR consistently.
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        normalized = normalized.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return normalized;
    }

    private boolean appendByteFallback(String symbol, List<Integer> output) {
        byte[] utf8 = symbol.getBytes(StandardCharsets.UTF_8);
        ArrayList<Integer> byteIds = new ArrayList<>(utf8.length);
        for (byte value : utf8) {
            Integer id = pieceToId.get(bytePieceName(value & 0xFF));
            if (id == null) {
                return false;
            }
            byteIds.add(id);
        }
        output.addAll(byteIds);
        return true;
    }

    private boolean hasAllBytePieces() {
        for (int i = 0; i < 256; i++) {
            if (!pieceToId.containsKey(bytePieceName(i))) {
                return false;
            }
        }
        return true;
    }

    private static String bytePieceName(int value) {
        return String.format(java.util.Locale.ROOT, "<0x%02X>", value);
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

    private void ensureLoaded() {
        if (!isLoaded()) {
            throw new IllegalStateException("SentencePiece model is not loaded");
        }
    }

    private static byte[] readAll(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    // ---------------------------------------------------------------------
    // Minimal protobuf decoding for SentencePiece's ModelProto.
    // ---------------------------------------------------------------------

    private static List<ParsedPiece> parseModelProto(byte[] data) throws IOException {
        ArrayList<ParsedPiece> result = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            long tag = readVarint(buffer);
            int fieldNumber = (int) (tag >>> 3);
            int wireType = (int) (tag & 0x7);
            if (fieldNumber == 1 && wireType == 2) {
                byte[] pieceBytes = readLengthDelimited(buffer);
                result.add(parseSentencePiece(pieceBytes));
            } else {
                skipField(buffer, wireType);
            }
        }
        return result;
    }

    private static ParsedPiece parseSentencePiece(byte[] data) throws IOException {
        String piece = "";
        float score = 0f;
        int type = 1;
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            long tag = readVarint(buffer);
            int fieldNumber = (int) (tag >>> 3);
            int wireType = (int) (tag & 0x7);
            if (fieldNumber == 1 && wireType == 2) {
                piece = new String(readLengthDelimited(buffer), StandardCharsets.UTF_8);
            } else if (fieldNumber == 2 && wireType == 5) {
                requireRemaining(buffer, 4);
                score = buffer.getFloat();
            } else if (fieldNumber == 3 && wireType == 0) {
                type = (int) readVarint(buffer);
            } else {
                skipField(buffer, wireType);
            }
        }
        return new ParsedPiece(piece, score, type);
    }

    private static byte[] readLengthDelimited(ByteBuffer buffer) throws IOException {
        long lengthLong = readVarint(buffer);
        if (lengthLong < 0 || lengthLong > Integer.MAX_VALUE
                || lengthLong > buffer.remaining()) {
            throw new IOException("Invalid SentencePiece protobuf length: " + lengthLong);
        }
        int length = (int) lengthLong;
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    private static long readVarint(ByteBuffer buffer) throws IOException {
        long result = 0;
        int shift = 0;
        while (buffer.hasRemaining()) {
            int current = buffer.get() & 0xFF;
            if (shift == 63 && (current & 0xFE) != 0) {
                throw new IOException("Invalid SentencePiece protobuf varint");
            }
            result |= (long) (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new IOException("Invalid SentencePiece protobuf varint");
            }
        }
        throw new IOException("Truncated SentencePiece protobuf varint");
    }

    private static void skipField(ByteBuffer buffer, int wireType) throws IOException {
        switch (wireType) {
            case 0:
                readVarint(buffer);
                break;
            case 1:
                requireRemaining(buffer, 8);
                buffer.position(buffer.position() + 8);
                break;
            case 2: {
                long length = readVarint(buffer);
                if (length < 0 || length > buffer.remaining()) {
                    throw new IOException("Invalid SentencePiece protobuf field length");
                }
                buffer.position(buffer.position() + (int) length);
                break;
            }
            case 5:
                requireRemaining(buffer, 4);
                buffer.position(buffer.position() + 4);
                break;
            default:
                throw new IOException("Unsupported SentencePiece protobuf wire type: "
                        + wireType);
        }
    }

    private static void requireRemaining(ByteBuffer buffer, int count)
            throws IOException {
        if (buffer.remaining() < count) {
            throw new IOException("Truncated SentencePiece protobuf field");
        }
    }
}
