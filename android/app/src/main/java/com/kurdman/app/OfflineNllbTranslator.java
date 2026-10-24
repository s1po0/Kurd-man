package com.kurdman.app;

import android.util.Log;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Fully on-device Sorani translation with Meta NLLB-200 distilled 600M.
 *
 * <p>This uses the quantized encoder plus two separate decoder graphs. The
 * separate decoder/decoder-with-past pair is the Android-safe design proven
 * by the MIT-licensed InstantVoiceTranslate project; the single merged
 * decoder's ONNX If branch can produce empty encoder KV tensors on Android.
 * Sessions are owned by one translation job and closed by
 * {@link SoraniTranslator#close()} to release several hundred MB of RAM.</p>
 */
final class OfflineNllbTranslator
        implements SoraniTranslator.OfflineEngine, AutoCloseable {
    private static final String TAG = "OfflineNllbTranslator";
    private static final int MAX_INPUT_TOKENS = 192;
    private static final int MAX_CHUNK_CHARS = 220;
    private static final int MAX_OUTPUT_TOKENS = 192;
    private static final int CACHE_SIZE = 256;

    private final OfflineModelManager modelManager;
    private final Map<String, String> cache =
            new LinkedHashMap<String, String>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, String> eldest) {
                    return size() > CACHE_SIZE;
                }
            };

    private OrtEnvironment environment;
    private OrtSession.SessionOptions sessionOptions;
    private OrtSession encoderSession;
    private OrtSession decoderSession;
    private OrtSession decoderWithPastSession;
    private NllbTokenizer tokenizer;

    OfflineNllbTranslator(OfflineModelManager modelManager) {
        this.modelManager = modelManager;
    }

    synchronized void initialize() throws Exception {
        if (isInitialized()) {
            return;
        }
        if (!modelManager.isModelReady()) {
            throw new IOException("OFFLINE_MODEL_NOT_READY: "
                    + "The NLLB model has not been downloaded and verified");
        }

        try {
            environment = OrtEnvironment.getEnvironment();
            sessionOptions = new OrtSession.SessionOptions();
            // Phones with little RAM were lagging badly or being killed
            // while the three ~450 MB sessions loaded/ran. On those, trade
            // some speed for a much lighter footprint: fewer compute
            // threads (less heat/CPU saturation), no CPU arena (ORT's
            // arena retains large blocks instead of returning them to the
            // OS), and only basic graph optimizations (lower load spike).
            long totalRam = modelManager.totalRamBytes();
            boolean lowRam = totalRam > 0 && totalRam < 5_000_000_000L;
            int threads = lowRam ? 2 : Math.max(2, Math.min(4,
                    Runtime.getRuntime().availableProcessors()));
            if (lowRam) {
                sessionOptions.setCPUArenaAllocator(false);
                sessionOptions.setOptimizationLevel(
                        OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            }
            sessionOptions.setIntraOpNumThreads(threads);
            sessionOptions.setInterOpNumThreads(1);

            encoderSession = createSession(OfflineModelManager.ENCODER_FILE);
            decoderSession = createSession(OfflineModelManager.DECODER_FILE);
            decoderWithPastSession = createSession(
                    OfflineModelManager.DECODER_WITH_PAST_FILE);

            requireInputs(encoderSession, "encoder",
                    "input_ids", "attention_mask");
            requireInputs(decoderSession, "decoder",
                    "input_ids", "encoder_hidden_states", "encoder_attention_mask");
            requireInputs(decoderWithPastSession, "decoder_with_past", "input_ids");

            NllbTokenizer loadedTokenizer = new NllbTokenizer();
            loadedTokenizer.initialize(
                    modelManager.modelFile(OfflineModelManager.TOKENIZER_FILE));
            tokenizer = loadedTokenizer;

            Log.i(TAG, "Offline NLLB ready (threads=" + threads
                    + ", totalRam=" + totalRam + ", lowRam=" + lowRam
                    + ", decoderPastInputs="
                    + decoderWithPastSession.getInputNames().size() + ")");
        } catch (IOException e) {
            close();
            throw e;
        } catch (OrtException e) {
            close();
            throw new IOException("OFFLINE_AI_RUNTIME_ERROR: " + e.getMessage(), e);
        } catch (OutOfMemoryError e) {
            close();
            throw new IOException("OFFLINE_AI_OUT_OF_MEMORY: "
                    + "Not enough free RAM to load the offline model", e);
        } catch (LinkageError e) {
            close();
            throw new IOException("OFFLINE_AI_RUNTIME_ERROR: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            close();
            throw new IOException("OFFLINE_AI_RUNTIME_ERROR: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized String translate(
            String text, String sourceLanguage) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        try {
            initialize();
            String clean = text.replaceAll("\\s+", " ").trim();
            List<String> chunks = splitForModel(clean);
            StringBuilder joined = new StringBuilder();
            for (String chunk : chunks) {
                checkInterrupted();
                if (chunk.isEmpty()) {
                    continue;
                }
                String key = sourceLanguage + "\n" + chunk;
                String translated = cache.get(key);
                if (translated == null) {
                    translated = cleanupTranslatedText(
                            runInference(chunk, sourceLanguage));
                    if (!translated.isEmpty()) {
                        cache.put(key, translated);
                    }
                }
                if (!translated.isEmpty()) {
                    if (joined.length() > 0) {
                        joined.append(' ');
                    }
                    joined.append(translated);
                }
            }
            String result = cleanupTranslatedText(joined.toString());
            if (result.isEmpty()) {
                throw new IOException("OFFLINE_AI_EMPTY_OUTPUT: "
                        + "The offline model returned no text");
            }
            return result;
        } catch (OutOfMemoryError e) {
            close();
            throw new IOException("OFFLINE_AI_OUT_OF_MEMORY: "
                    + "Not enough free RAM for offline translation", e);
        } catch (LinkageError e) {
            close();
            throw new IOException("OFFLINE_AI_RUNTIME_ERROR: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        closeSession(encoderSession);
        encoderSession = null;
        closeSession(decoderSession);
        decoderSession = null;
        closeSession(decoderWithPastSession);
        decoderWithPastSession = null;
        if (tokenizer != null) {
            tokenizer.close();
            tokenizer = null;
        }
        if (sessionOptions != null) {
            try {
                sessionOptions.close();
            } catch (Exception ignored) {
                // Sessions are already closed; options cleanup is best effort.
            }
            sessionOptions = null;
        }
        environment = null;
    }

    private boolean isInitialized() {
        return environment != null
                && encoderSession != null
                && decoderSession != null
                && decoderWithPastSession != null
                && tokenizer != null
                && tokenizer.isInitialized();
    }

    private OrtSession createSession(String fileName) throws OrtException {
        return environment.createSession(
                modelManager.modelFile(fileName).getAbsolutePath(), sessionOptions);
    }

    private void requireInputs(OrtSession session, String label,
                               String... required) throws IOException {
        Set<String> names = session.getInputNames();
        for (String name : required) {
            if (!names.contains(name)) {
                throw new IOException("OFFLINE_AI_MODEL_MISMATCH: "
                        + label + " is missing input " + name);
            }
        }
    }

    private String runInference(String text, String sourceLanguage) throws Exception {
        OrtEnvironment env = environment;
        OrtSession encoder = encoderSession;
        OrtSession decoder = decoderSession;
        OrtSession decoderWithPast = decoderWithPastSession;
        NllbTokenizer tok = tokenizer;
        if (env == null || encoder == null || decoder == null
                || decoderWithPast == null || tok == null) {
            throw new IOException("OFFLINE_AI_RUNTIME_ERROR: model is not initialized");
        }

        long[] inputIds = tok.encode(text, sourceLanguage);
        if (inputIds.length > 512) {
            // splitForModel should prevent this; preserve the language token
            // and EOS if a tokenizer edge case still produces a huge chunk.
            long[] truncated = new long[512];
            System.arraycopy(inputIds, 0, truncated, 0, truncated.length - 1);
            truncated[truncated.length - 1] = NllbTokenizer.EOS_TOKEN_ID;
            inputIds = truncated;
        }

        OnnxTensor inputIdsTensor = null;
        OnnxTensor attentionMaskTensor = null;
        OnnxTensor firstDecoderInput = null;
        OrtSession.Result encoderOutputs = null;
        OrtSession.Result firstOutputs = null;
        Map<String, OnnxTensor> encoderKvCache = new LinkedHashMap<>();
        Map<String, OnnxTensor> decoderKvCache = new LinkedHashMap<>();

        try {
            long[] attentionMask = new long[inputIds.length];
            for (int i = 0; i < attentionMask.length; i++) {
                attentionMask[i] = 1L;
            }
            inputIdsTensor = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(inputIds),
                    new long[]{1L, inputIds.length});
            attentionMaskTensor = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(attentionMask),
                    new long[]{1L, attentionMask.length});

            Map<String, OnnxTensor> encoderInputs = new HashMap<>();
            encoderInputs.put("input_ids", inputIdsTensor);
            encoderInputs.put("attention_mask", attentionMaskTensor);
            encoderOutputs = encoder.run(encoderInputs);
            OnnxTensor encoderHiddenStates = outputTensor(
                    encoderOutputs, "last_hidden_state");

            long targetToken = tok.targetLanguageTokenId();
            firstDecoderInput = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(new long[]{NllbTokenizer.EOS_TOKEN_ID}),
                    new long[]{1L, 1L});
            Map<String, OnnxTensor> firstInputs = new HashMap<>();
            firstInputs.put("input_ids", firstDecoderInput);
            firstInputs.put("encoder_hidden_states", encoderHiddenStates);
            firstInputs.put("encoder_attention_mask", attentionMaskTensor);
            firstOutputs = decoder.run(firstInputs);

            Map<String, OnnxTensor> allKv = extractKvCache(
                    firstOutputs, decoderWithPast.getInputNames(), false);
            for (Map.Entry<String, OnnxTensor> entry : allKv.entrySet()) {
                if (entry.getKey().contains(".encoder.")) {
                    encoderKvCache.put(entry.getKey(), entry.getValue());
                } else {
                    decoderKvCache.put(entry.getKey(), entry.getValue());
                }
            }
            if (decoderKvCache.isEmpty()) {
                throw new IOException("OFFLINE_AI_MODEL_MISMATCH: "
                        + "decoder produced no KV cache");
            }

            closeResult(firstOutputs);
            firstOutputs = null;
            closeTensor(firstDecoderInput);
            firstDecoderInput = null;

            ArrayList<Long> outputTokens = new ArrayList<>();
            outputTokens.add(targetToken);
            long nextDecoderInput = targetToken;
            int maxOutput = Math.min(MAX_OUTPUT_TOKENS,
                    Math.max(64, inputIds.length * 2 + 32));
            long lastGenerated = -1;
            int repeatedCount = 0;

            for (int step = 0; step < maxOutput; step++) {
                checkInterrupted();
                OnnxTensor stepInput = null;
                OrtSession.Result stepOutputs = null;
                Map<String, OnnxTensor> nextDecoderKvCache = null;
                try {
                    stepInput = OnnxTensor.createTensor(
                            env, LongBuffer.wrap(new long[]{nextDecoderInput}),
                            new long[]{1L, 1L});
                    Map<String, OnnxTensor> stepInputs = new HashMap<>();
                    stepInputs.put("input_ids", stepInput);
                    Set<String> expected = decoderWithPast.getInputNames();
                    if (expected.contains("encoder_hidden_states")) {
                        stepInputs.put("encoder_hidden_states", encoderHiddenStates);
                    }
                    if (expected.contains("encoder_attention_mask")) {
                        stepInputs.put("encoder_attention_mask", attentionMaskTensor);
                    }
                    stepInputs.putAll(decoderKvCache);
                    stepInputs.putAll(encoderKvCache);

                    stepOutputs = decoderWithPast.run(stepInputs);
                    OnnxTensor logits = outputTensor(stepOutputs, "logits");
                    long nextToken = argmax(logits, tok);
                    if (nextToken == NllbTokenizer.EOS_TOKEN_ID) {
                        break;
                    }

                    outputTokens.add(nextToken);
                    if (nextToken == lastGenerated) {
                        repeatedCount++;
                    } else {
                        lastGenerated = nextToken;
                        repeatedCount = 1;
                    }
                    if (repeatedCount >= 8) {
                        break;
                    }

                    nextDecoderKvCache = extractKvCache(
                            stepOutputs, expected, true);
                    if (nextDecoderKvCache.isEmpty()) {
                        throw new IOException("OFFLINE_AI_MODEL_MISMATCH: "
                                + "decoder_with_past produced no KV cache");
                    }
                    closeTensorMap(decoderKvCache);
                    decoderKvCache = nextDecoderKvCache;
                    nextDecoderKvCache = null;
                    nextDecoderInput = nextToken;
                } finally {
                    if (nextDecoderKvCache != null) {
                        closeTensorMap(nextDecoderKvCache);
                    }
                    if (stepOutputs != null) {
                        closeResult(stepOutputs);
                    }
                    if (stepInput != null) {
                        closeTensor(stepInput);
                    }
                }
            }

            return tok.decode(toLongArray(outputTokens));
        } finally {
            closeTensorMap(decoderKvCache);
            closeTensorMap(encoderKvCache);
            if (firstOutputs != null) {
                closeResult(firstOutputs);
            }
            if (firstDecoderInput != null) {
                closeTensor(firstDecoderInput);
            }
            if (encoderOutputs != null) {
                closeResult(encoderOutputs);
            }
            if (inputIdsTensor != null) {
                closeTensor(inputIdsTensor);
            }
            if (attentionMaskTensor != null) {
                closeTensor(attentionMaskTensor);
            }
        }
    }

    private Map<String, OnnxTensor> extractKvCache(
            OrtSession.Result outputs,
            Set<String> expectedInputNames,
            boolean decoderOnly) throws IOException, OrtException {
        Map<String, OnnxTensor> cache = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, OnnxValue> entry : outputs) {
                String outputName = entry.getKey();
                if (!outputName.startsWith("present.")) {
                    continue;
                }
                if (decoderOnly && outputName.contains(".encoder.")) {
                    continue;
                }
                String inputName = "past_key_values"
                        + outputName.substring("present".length());
                if (!expectedInputNames.contains(inputName)) {
                    continue;
                }
                OnnxValue value = entry.getValue();
                if (!(value instanceof OnnxTensor)) {
                    continue;
                }
                OnnxTensor tensor = (OnnxTensor) value;
                long[] shape = tensor.getInfo().getShape();
                for (long dimension : shape) {
                    if (dimension <= 0) {
                        throw new IOException("OFFLINE_AI_MODEL_MISMATCH: "
                                + "empty KV tensor " + outputName);
                    }
                }
                cache.put(inputName, cloneTensor(tensor));
            }
            return cache;
        } catch (IOException | OrtException | RuntimeException e) {
            closeTensorMap(cache);
            throw e;
        }
    }

    private OnnxTensor cloneTensor(OnnxTensor tensor)
            throws IOException, OrtException {
        long[] shape = tensor.getInfo().getShape();
        long count = 1;
        for (long dimension : shape) {
            if (dimension <= 0 || count > Integer.MAX_VALUE / dimension) {
                throw new IOException("OFFLINE_AI_MODEL_MISMATCH: "
                        + "invalid tensor shape");
            }
            count *= dimension;
        }
        FloatBuffer source = tensor.getFloatBuffer();
        if (source == null) {
            throw new IOException("OFFLINE_AI_MODEL_MISMATCH: "
                    + "unsupported KV tensor type "
                    + tensor.getInfo().type);
        }
        FloatBuffer copy = source.duplicate();
        copy.rewind();
        if (copy.remaining() < count) {
            throw new IOException("OFFLINE_AI_MODEL_MISMATCH: "
                    + "KV tensor buffer is shorter than its shape");
        }
        float[] data = new float[(int) count];
        copy.get(data);
        return OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(data), shape);
    }

    private OnnxTensor outputTensor(OrtSession.Result outputs,
                                    String preferredName) throws IOException {
        OnnxValue value = outputs.get(preferredName).orElse(outputs.get(0));
        if (!(value instanceof OnnxTensor)) {
            throw new IOException("OFFLINE_AI_MODEL_MISMATCH: "
                    + "expected tensor output " + preferredName);
        }
        return (OnnxTensor) value;
    }

    private long argmax(OnnxTensor logits, NllbTokenizer tok) throws IOException {
        long[] shape = logits.getInfo().getShape();
        if (shape.length == 0 || shape[shape.length - 1] <= 0
                || shape[shape.length - 1] > Integer.MAX_VALUE) {
            throw new IOException("OFFLINE_AI_MODEL_MISMATCH: invalid logits shape");
        }
        if (logits.getInfo().type != OnnxJavaType.FLOAT
                && logits.getInfo().type != OnnxJavaType.FLOAT16
                && logits.getInfo().type != OnnxJavaType.BFLOAT16) {
            throw new IOException("OFFLINE_AI_MODEL_MISMATCH: "
                    + "unsupported logits type " + logits.getInfo().type);
        }
        FloatBuffer buffer = logits.getFloatBuffer();
        if (buffer == null) {
            throw new IOException("OFFLINE_AI_MODEL_MISMATCH: missing logits buffer");
        }
        int vocabulary = (int) shape[shape.length - 1];
        int count = Math.min(vocabulary, buffer.remaining());
        long bestToken = NllbTokenizer.EOS_TOKEN_ID;
        float bestValue = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            // Never feed BOS/PAD or another language-control token back into
            // the decoder. EOS (2) and UNK (3) remain possible outputs.
            if (i == 0 || i == 1 || tok.isNonTextToken(i)) {
                continue;
            }
            float value = buffer.get(i);
            if (!Float.isNaN(value) && value > bestValue) {
                bestValue = value;
                bestToken = i;
            }
        }
        return bestToken;
    }

    private List<String> splitForModel(String text) {
        List<String> sentences = splitSentences(text);
        List<String> combined = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String clean = sentence.trim();
            if (clean.isEmpty()) {
                continue;
            }
            if (current.length() == 0) {
                current.append(clean);
            } else if (current.length() + 1 + clean.length() <= MAX_CHUNK_CHARS) {
                current.append(' ').append(clean);
            } else {
                combined.add(current.toString());
                current.setLength(0);
                current.append(clean);
            }
        }
        if (current.length() > 0) {
            combined.add(current.toString());
        }

        List<String> result = new ArrayList<>();
        for (String chunk : combined) {
            splitByTokenLimit(chunk, result);
        }
        return result;
    }

    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int nextOffset = offset + Character.charCount(codePoint);
            current.appendCodePoint(codePoint);
            if (isSentenceTerminator(codePoint)) {
                int next = nextOffset < text.length()
                        ? text.codePointAt(nextOffset) : -1;
                boolean decimal = codePoint == '.'
                        && next >= '0' && next <= '9';
                if (!decimal && (next == -1 || Character.isWhitespace(next)
                        || isCjkOrHangul(next))) {
                    String sentence = current.toString().trim();
                    if (!sentence.isEmpty()) {
                        sentences.add(sentence);
                    }
                    current.setLength(0);
                }
            }
            offset = nextOffset;
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            sentences.add(tail);
        }
        return sentences;
    }

    private void splitByTokenLimit(String text, List<String> output) {
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) {
            return;
        }
        if (tokenizer.tokenCount(clean) <= MAX_INPUT_TOKENS) {
            output.add(clean);
            return;
        }
        int split = findSplitIndex(clean);
        if (split <= 0 || split >= clean.length()) {
            // Extremely defensive fallback: split on a code-point boundary.
            split = codePointBoundaryNear(clean, clean.length() / 2);
        }
        if (split <= 0 || split >= clean.length()) {
            output.add(clean);
            return;
        }
        splitByTokenLimit(clean.substring(0, split), output);
        splitByTokenLimit(clean.substring(split), output);
    }

    private int findSplitIndex(String text) {
        int target = text.length() / 2;
        int best = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int nextOffset = offset + Character.charCount(codePoint);
            boolean punctuation = isClausePunctuation(codePoint);
            boolean whitespace = Character.isWhitespace(codePoint);
            if ((punctuation || whitespace)
                    && nextOffset > text.length() / 4
                    && nextOffset < (text.length() * 3) / 4) {
                int score = Math.abs(nextOffset - target)
                        - (punctuation ? 24 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = nextOffset;
                }
            }
            offset = nextOffset;
        }
        return best;
    }

    private int codePointBoundaryNear(String text, int target) {
        int offset = Math.max(0, Math.min(text.length(), target));
        while (offset > 0 && offset < text.length()
                && Character.isLowSurrogate(text.charAt(offset))) {
            offset--;
        }
        return offset;
    }

    private static boolean isSentenceTerminator(int codePoint) {
        return codePoint == '.' || codePoint == '!' || codePoint == '?'
                || codePoint == '…' || codePoint == '。'
                || codePoint == '！' || codePoint == '？';
    }

    private static boolean isClausePunctuation(int codePoint) {
        return codePoint == ',' || codePoint == ';' || codePoint == ':'
                || codePoint == '،' || codePoint == '؛'
                || codePoint == '、' || codePoint == '，'
                || codePoint == '.' || codePoint == '!' || codePoint == '?'
                || codePoint == '。' || codePoint == '！' || codePoint == '？';
    }

    private static boolean isCjkOrHangul(int codePoint) {
        return (codePoint >= 0x2E80 && codePoint <= 0x9FFF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF);
    }

    private static String cleanupTranslatedText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u2581', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static long[] toLongArray(List<Long> values) {
        long[] result = new long[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static void closeTensorMap(Map<String, OnnxTensor> tensors) {
        if (tensors == null) {
            return;
        }
        for (OnnxTensor tensor : tensors.values()) {
            closeTensor(tensor);
        }
        tensors.clear();
    }

    private static void closeTensor(OnnxTensor tensor) {
        if (tensor != null) {
            try {
                tensor.close();
            } catch (RuntimeException ignored) {
                // Native tensor cleanup is best effort during error handling.
            }
        }
    }

    private static void closeResult(OrtSession.Result result) {
        if (result != null) {
            try {
                result.close();
            } catch (RuntimeException ignored) {
                // Native result cleanup is best effort during error handling.
            }
        }
    }

    private static void closeSession(OrtSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
                // The field is cleared by the caller either way.
            }
        }
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Offline translation interrupted");
        }
    }
}
