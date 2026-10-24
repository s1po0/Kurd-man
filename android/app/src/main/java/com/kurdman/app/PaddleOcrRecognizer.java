package com.kurdman.app;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PP-OCR line recognizer running only inside the isolated OCR process.
 *
 * <p>ML Kit remains responsible for finding the text lines; this class
 * re-reads one already-cropped horizontal line with PP-OCRv6 small
 * (English/Japanese/Chinese) or PP-OCRv5 mobile (Korean), then performs
 * greedy CTC decoding. It is deliberately a second opinion rather than a
 * blind replacement: callers compare its confidence with ML Kit before
 * changing any word.</p>
 */
final class PaddleOcrRecognizer implements Closeable {
    private static final String TAG = "PaddleOcrRecognizer";
    private static final int DEFAULT_HEIGHT = 48;
    private static final int DEFAULT_WIDTH = 320;
    private static final int MAX_WIDTH = 2048;

    static final class Result {
        final String text;
        final float confidence;

        Result(String text, float confidence) {
            this.text = text == null ? "" : text;
            this.confidence = confidence;
        }
    }

    private final boolean korean;
    private final OrtEnvironment environment;
    private final OrtSession.SessionOptions sessionOptions;
    private final OrtSession session;
    private final String inputName;
    private final long[] inputShape;
    private final List<String> charset;

    PaddleOcrRecognizer(Context context, String sourceLanguage) throws Exception {
        PaddleOcrModelManager manager = new PaddleOcrModelManager(context);
        korean = sourceLanguage != null
                && sourceLanguage.toLowerCase(Locale.ROOT).startsWith("ko");
        if (!manager.isReadyFor(sourceLanguage)) {
            throw new IOException("PPOCR_MODEL_NOT_READY: "
                    + "The PP-OCR pack has not been downloaded and verified");
        }
        File modelFile = manager.modelFile(korean
                ? PaddleOcrModelManager.KOREAN_MODEL_FILE
                : PaddleOcrModelManager.GENERAL_MODEL_FILE);
        File dictFile = manager.modelFile(korean
                ? PaddleOcrModelManager.KOREAN_DICT_FILE
                : PaddleOcrModelManager.GENERAL_DICT_FILE);

        environment = OrtEnvironment.getEnvironment("kurdman-ppocr");
        sessionOptions = new OrtSession.SessionOptions();
        try {
            sessionOptions.addConfigEntry("session.disable_prepacking", "1");
            sessionOptions.addConfigEntry("mlas.disable_kleidiai", "1");
        } catch (OrtException | RuntimeException ignored) {
            // Optional stability keys must never prevent the model loading.
        }
        sessionOptions.setIntraOpNumThreads(2);
        sessionOptions.setInterOpNumThreads(1);
        session = environment.createSession(
                modelFile.getAbsolutePath(), sessionOptions);
        if (session.getInputNames().isEmpty()) {
            throw new IOException("PPOCR_MODEL_MISMATCH: model has no inputs");
        }
        inputName = session.getInputNames().iterator().next();
        inputShape = readInputShape(session, inputName);
        charset = loadCharset(session, dictFile);
        Log.i(TAG, "PP-OCR ready: korean=" + korean
                + ", input=" + inputName
                + ", classes=" + charset.size());
    }

    synchronized Result recognize(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return new Result("", 0f);
        }
        OnnxTensor tensor = null;
        OrtSession.Result outputs = null;
        Bitmap resized = null;
        try {
            InputSize size = resolveInputSize(bitmap.getWidth(), bitmap.getHeight());
            resized = Bitmap.createScaledBitmap(
                    bitmap, size.contentWidth, size.height, true);
            float[] input = new float[3 * size.height * size.width];
            int[] pixels = new int[size.contentWidth * size.height];
            resized.getPixels(pixels, 0, size.contentWidth,
                    0, 0, size.contentWidth, size.height);
            int plane = size.height * size.width;
            for (int y = 0; y < size.height; y++) {
                for (int x = 0; x < size.contentWidth; x++) {
                    int pixel = pixels[y * size.contentWidth + x];
                    int base = y * size.width + x;
                    // PaddleOCR's exported recognizers consume BGR order.
                    input[base] = normalize(Color.blue(pixel));
                    input[base + plane] = normalize(Color.green(pixel));
                    input[base + (2 * plane)] = normalize(Color.red(pixel));
                }
            }

            tensor = OnnxTensor.createTensor(environment,
                    FloatBuffer.wrap(input),
                    new long[]{1L, 3L, size.height, size.width});
            outputs = session.run(Collections.singletonMap(inputName, tensor));
            Object value = outputs.get(0).getValue();
            return ctcDecode(value);
        } catch (Throwable e) {
            Log.w(TAG, "PP-OCR inference failed", e);
            return new Result("", 0f);
        } finally {
            closeQuietly(outputs);
            closeQuietly(tensor);
            if (resized != null && resized != bitmap) {
                resized.recycle();
            }
        }
    }

    @Override
    public synchronized void close() {
        try {
            session.close();
        } catch (Throwable ignored) {
            // Process shutdown will reclaim native resources.
        }
        try {
            sessionOptions.close();
        } catch (Throwable ignored) {
            // Process shutdown will reclaim native resources.
        }
    }

    private static float normalize(int channel) {
        return ((channel / 255f) - 0.5f) / 0.5f;
    }

    private InputSize resolveInputSize(int sourceWidth, int sourceHeight) {
        int height = inputShape != null && inputShape.length > 2
                && inputShape[2] > 0 && inputShape[2] <= 512
                ? (int) inputShape[2] : DEFAULT_HEIGHT;
        int requiredWidth = Math.max(32, (int) Math.ceil(
                height * Math.max(1, sourceWidth)
                        / (double) Math.max(1, sourceHeight)));
        long fixedWidth = inputShape != null && inputShape.length > 3
                ? inputShape[3] : -1;
        int width;
        if (fixedWidth > 0 && fixedWidth <= 8192) {
            width = (int) fixedWidth;
        } else if (requiredWidth <= DEFAULT_WIDTH) {
            width = DEFAULT_WIDTH;
        } else if (requiredWidth >= MAX_WIDTH) {
            width = MAX_WIDTH;
        } else {
            width = ((requiredWidth + 31) / 32) * 32;
        }
        return new InputSize(height, width, Math.min(requiredWidth, width));
    }

    private Result ctcDecode(Object rawValue) {
        List<float[]> steps = toSteps(rawValue);
        if (steps.isEmpty()) {
            return new Result("", 0f);
        }
        StringBuilder text = new StringBuilder();
        double confidenceSum = 0.0;
        int tokenCount = 0;
        int previous = -1;
        for (float[] values : steps) {
            if (values == null || values.length == 0) {
                continue;
            }
            int bestIndex = 0;
            float bestValue = values[0];
            boolean probabilityLike = true;
            for (int i = 0; i < values.length; i++) {
                float value = values[i];
                if (value < 0f || value > 1.0001f || Float.isNaN(value)) {
                    probabilityLike = false;
                }
                if (value > bestValue) {
                    bestValue = value;
                    bestIndex = i;
                }
            }
            if (bestIndex == previous) {
                continue;
            }
            previous = bestIndex;
            if (bestIndex == 0 || bestIndex >= charset.size()) {
                continue;
            }
            text.append(charset.get(bestIndex));
            confidenceSum += probabilityLike
                    ? Math.max(0f, Math.min(1f, bestValue))
                    : softmaxProbability(values, bestIndex, bestValue);
            tokenCount++;
        }
        String cleaned = text.toString()
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (korean) {
            cleaned = trimSuspiciousKoreanTail(cleaned);
        }
        float confidence = tokenCount == 0 ? 0f
                : (float) (confidenceSum / (double) tokenCount);
        return new Result(cleaned, confidence);
    }

    private static double softmaxProbability(float[] values, int bestIndex,
                                             float bestValue) {
        double denominator = 0.0;
        for (float value : values) {
            denominator += Math.exp(value - bestValue);
        }
        return denominator <= 0.0 ? 0.0 : 1.0 / denominator;
    }

    private static String trimSuspiciousKoreanTail(String text) {
        int end = text.length();
        while (end > 0) {
            int codePoint = text.codePointBefore(end);
            boolean hangul = (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                    || (codePoint >= 0x3131 && codePoint <= 0x318E)
                    || (codePoint >= 0x1100 && codePoint <= 0x11FF);
            if (hangul || Character.isLetterOrDigit(codePoint)
                    || Character.isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return end == text.length() ? text : text.substring(0, end).trim();
    }

    private static List<float[]> toSteps(Object value) {
        if (value instanceof float[][][]) {
            float[][][] array = (float[][][]) value;
            if (array.length == 0 || array[0] == null) {
                return Collections.emptyList();
            }
            if (array.length == 1) {
                return Arrays.asList(array[0]);
            }
            if (array[0].length == 1) {
                List<float[]> steps = new ArrayList<>(array.length);
                for (float[][] step : array) {
                    if (step != null && step.length > 0) {
                        steps.add(step[0]);
                    }
                }
                return steps;
            }
            return Arrays.asList(array[0]);
        }
        if (value instanceof float[][]) {
            return Arrays.asList((float[][]) value);
        }
        if (value instanceof float[][][][]) {
            float[][][][] array = (float[][][][]) value;
            if (array.length > 0 && array[0] != null && array[0].length > 0) {
                return Arrays.asList(array[0][0]);
            }
        }
        return Collections.emptyList();
    }

    private static long[] readInputShape(OrtSession session, String inputName) {
        try {
            Map<String, NodeInfo> info = session.getInputInfo();
            NodeInfo node = info == null ? null : info.get(inputName);
            if (node != null && node.getInfo() instanceof TensorInfo) {
                return ((TensorInfo) node.getInfo()).getShape();
            }
        } catch (Throwable ignored) {
            // The PP-OCR defaults below match the official mobile exports.
        }
        return new long[]{-1, 3, DEFAULT_HEIGHT, -1};
    }

    private static List<String> loadCharset(OrtSession session, File dictFile)
            throws IOException {
        List<String> fromMetadata = charsetFromMetadata(session);
        if (fromMetadata != null && fromMetadata.size() > 500) {
            return fromMetadata;
        }
        List<String> entries = parseYamlCharacterDict(dictFile);
        List<String> built = buildCharset(entries);
        if (built.size() <= 500) {
            throw new IOException("PPOCR_MODEL_MISMATCH: "
                    + "character dictionary is missing or too small");
        }
        return built;
    }

    private static List<String> charsetFromMetadata(OrtSession session) {
        try {
            Map<String, String> custom = session.getMetadata().getCustomMetadata();
            String raw = custom == null ? null : custom.get("character");
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            String[] lines = raw.split("\\r?\\n", -1);
            List<String> entries = new ArrayList<>();
            if (lines.length == 1 && lines[0].length() > 500) {
                String joined = lines[0];
                for (int offset = 0; offset < joined.length();) {
                    int next = joined.offsetByCodePoints(offset, 1);
                    entries.add(joined.substring(offset, next));
                    offset = next;
                }
            } else {
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        entries.add(line);
                    }
                }
            }
            return buildCharset(entries);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<String> parseYamlCharacterDict(File file) throws IOException {
        List<String> entries = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            boolean inDict = false;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!inDict) {
                    if (trimmed.equals("character_dict:")) {
                        inDict = true;
                    }
                    continue;
                }
                if (!trimmed.startsWith("-")) {
                    if (!trimmed.isEmpty()) {
                        break;
                    }
                    continue;
                }
                String raw = trimmed.length() == 1 ? "" : trimmed.substring(2);
                String value = parseYamlScalar(raw);
                if (!value.isEmpty() || raw.trim().equals("' '")
                        || raw.trim().equals("\" \"")) {
                    entries.add(value);
                }
            }
        } finally {
            reader.close();
        }
        return entries;
    }

    private static String parseYamlScalar(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.length() >= 2 && value.charAt(0) == '\'') {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\'') {
                    if (i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                        out.append('\'');
                        i++;
                    } else {
                        break;
                    }
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        if (value.length() >= 2 && value.charAt(0) == '"') {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && i + 1 < value.length()) {
                    char next = value.charAt(++i);
                    switch (next) {
                        case 'n': out.append('\n'); break;
                        case 't': out.append('\t'); break;
                        case 'r': out.append('\r'); break;
                        case '\\': out.append('\\'); break;
                        case '"': out.append('"'); break;
                        case 'u':
                            if (i + 4 < value.length()) {
                                try {
                                    out.append((char) Integer.parseInt(
                                            value.substring(i + 1, i + 5), 16));
                                    i += 4;
                                } catch (NumberFormatException ignored) {
                                    out.append('u');
                                }
                            } else {
                                out.append('u');
                            }
                            break;
                        default: out.append(next); break;
                    }
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        return value;
    }

    private static List<String> buildCharset(List<String> entries) {
        List<String> clean = new ArrayList<>();
        for (String entry : entries) {
            if (entry != null && !entry.isEmpty()) {
                clean.add(entry);
            }
        }
        List<String> out = new ArrayList<>(clean.size() + 2);
        if (clean.isEmpty() || !"blank".equals(clean.get(0))) {
            out.add("blank");
        }
        out.addAll(clean);
        if (out.isEmpty() || !" ".equals(out.get(out.size() - 1))) {
            out.add(" ");
        }
        return out;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Throwable ignored) {
                // Inference cleanup is best-effort.
            }
        }
    }

    private static final class InputSize {
        final int height;
        final int width;
        final int contentWidth;

        InputSize(int height, int width, int contentWidth) {
            this.height = height;
            this.width = width;
            this.contentWidth = contentWidth;
        }
    }
}
